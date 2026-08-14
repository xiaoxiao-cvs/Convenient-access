package com.shinoyuki.accesshub.deviceauth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 单个玩家一次连接内的免密握手状态机 (纯逻辑, 不引 Minecraft 类型, 可单测).
 *
 * 仅在服务器主线程访问 (PlayerLoggedInEvent / 包处理 enqueueWork / PlayerTickEvent), 故不加锁。
 *
 * 四条设计要点:
 *
 * 1. 挑战必须可重发: 服务端原先只在进服瞬间用 isRemotePresent 判一次客户端有没有本 mod, 而
 *    Connector/兼容层下 PLAY 通道协商可能晚于 PlayerLoggedInEvent 完成 -> 判成"没装"就再也不发,
 *    表现为免密整局不触发。故按 tick 复检并允许最多 MAX_AUTH_ATTEMPTS 次挑战。
 *
 * 2. 重发按时间间隔而非"上一条已过期": 重发间隔 (retryInterval) 取得比挑战有效期短, 新挑战发出时
 *    旧挑战仍然有效, 于是任意时刻都有可用 nonce, 客户端卡顿后补上的应答不会正好落进真空。
 *
 * 3. 验签期间冻结重发 (verifying): 应答一到就异步查库验签, 这一步可能被 SQLite WAL 写锁拖上几秒。
 *    若此时 tick 继续重发, 几个 tick 内就能把重试次数烧光。故验签在飞时既不重发也不受理新应答。
 *
 * 4. 挑战只在验签通过时消费: 验签不过就把挑战留着 —— 客户端可能只是对某条旧 nonce 迟到作答,
 *    此时若把在飞的挑战一并作废, 它随后对新挑战的正确应答就无处落脚。nonce 的单次使用由
 *    "通过即清空全部挑战"保证, 重放拿不到第二次认证。
 */
final class DeviceAuthSession {

    /** AUTH 挑战最大发送次数 (含首发)。 */
    static final int MAX_AUTH_ATTEMPTS = 4;

    /** 同时保留的未过期挑战上限. 重叠重发下正常最多 2 条, 留余量并防异常路径无限增长。 */
    private static final int MAX_LIVE_CHALLENGES = 4;

    /** 单次连接内允许的验签失败次数上限. 防被改造客户端反复灌应答空跑 Ed25519 验签。 */
    private static final int MAX_AUTH_FAILURES = 5;

    /** 已发出且尚未被成功验签消费的挑战, 队首最旧。 */
    private final Deque<Challenge> live = new ArrayDeque<>();

    private boolean joined;
    private boolean eligible;
    private boolean helloSeen;
    private boolean authDone;
    private boolean verifying;
    private boolean waitingChannelLogged;
    private int authAttempts;
    private int authFailures;
    /** 最近一次 AUTH 挑战的发出时刻 (ms); 0 表示还没发过。重发节流以它为准。 */
    private long lastAuthIssuedAt;

    /** PlayerLoggedInEvent 已处理完毕. eligible = 免密总开关开 + 未认证 + 该玩家库里有登记公钥。 */
    void markJoined(boolean eligible) {
        this.joined = true;
        this.eligible = eligible;
    }

    /** 客户端 hello 到达. 返回 false 表示重复 hello (忽略, 不给客户端反复触发挑战的机会)。 */
    boolean markHello() {
        if (helloSeen) {
            return false;
        }
        helloSeen = true;
        return true;
    }

    /** 收到过 hello 即确证对端装了本 mod, 无须再信服务端侧的通道登记视图。 */
    boolean helloSeen() {
        return helloSeen;
    }

    /** 通道未就绪的等待日志每会话只记一条 (按 tick 复检会刷屏)。 */
    boolean shouldLogWaitingChannel() {
        if (waitingChannelLogged) {
            return false;
        }
        waitingChannelLogged = true;
        return true;
    }

    /** 免密流程终结 (成功 / 确认密钥不配 / 次数用尽): 不再自动发 AUTH 挑战. /enroll 手动登记不受影响。 */
    void finishAuth() {
        authDone = true;
    }

    int authAttempts() {
        return authAttempts;
    }

    /** 清出所有已过期挑战, 返回清出条数 (>0 时调用方落一条超时日志, 便于线上区分"没发出去"与"发了没人应")。 */
    int expire(long now, long ttlMillis) {
        int expired = 0;
        for (Iterator<Challenge> it = live.iterator(); it.hasNext(); ) {
            if (now - it.next().issuedAt > ttlMillis) {
                it.remove();
                expired++;
            }
        }
        return expired;
    }

    /**
     * 是否该 (再) 发一次 AUTH 挑战: 进服已处理 + 有资格 + 未终结 + 没有验签在飞 + 次数未用尽 +
     * 距上次发出已超过重发间隔。
     *
     * 注意这里不要求"没有在飞挑战" —— 重发间隔比挑战有效期短, 故意让新旧挑战有一段重叠期。
     */
    boolean canIssueAuth(long now, long retryIntervalMillis) {
        return joined && eligible && !authDone && !verifying
                && authAttempts < MAX_AUTH_ATTEMPTS
                && (lastAuthIssuedAt == 0L || now - lastAuthIssuedAt >= retryIntervalMillis);
    }

    /** 次数用尽, 且没有挑战在飞也没有验签在飞: 调用方落一条放弃日志后 finishAuth。 */
    boolean authAttemptsExhausted() {
        return joined && eligible && !authDone && !verifying
                && live.isEmpty() && authAttempts >= MAX_AUTH_ATTEMPTS;
    }

    /** 登记一条已发出的挑战. 返回这是第几次 AUTH 挑战 (ENROLL 不计次, 返回 0)。 */
    int issue(String phase, byte[] nonce, long now, long codeId) {
        if (live.size() >= MAX_LIVE_CHALLENGES) {
            live.pollFirst();
        }
        live.addLast(new Challenge(phase, nonce, now, codeId));
        if (DeviceCrypto.PHASE_AUTH.equals(phase)) {
            lastAuthIssuedAt = now;
            return ++authAttempts;
        }
        return 0;
    }

    /**
     * 取该阶段所有未过期挑战作为试签候选, 按新到旧排列 (新挑战命中概率更高, 让验签尽早短路)。
     *
     * 只读: 挑战要留到验签通过 (endVerify(true)) 才消费, 否则一条迟到的旧应答会顺手作废掉
     * 客户端还没来得及作答的新挑战。
     */
    List<Challenge> candidates(String phase, long now, long ttlMillis) {
        List<Challenge> out = new ArrayList<>();
        for (Challenge c : live) {
            if (c.phase.equals(phase) && now - c.issuedAt <= ttlMillis) {
                out.add(c);
            }
        }
        Collections.reverse(out);
        return out;
    }

    /** 应答进入异步验签. 返回 false 表示已有验签在飞, 本次应答应被丢弃 (防灌包空跑验签)。 */
    boolean beginVerify() {
        if (verifying) {
            return false;
        }
        verifying = true;
        return true;
    }

    /** 异步验签回到主线程. granted 时清空全部挑战并终结: nonce 单次使用, 重放拿不到第二次认证。 */
    void endVerify(boolean granted) {
        verifying = false;
        if (granted) {
            live.clear();
            authDone = true;
        }
    }

    /**
     * 记一次 AUTH 验签失败. 返回 true 表示这已是最后机会 (再没有重发或失败预算),
     * 调用方据此把失败告知玩家并终结; 返回 false 则只记日志, 等下一次挑战 —— 因为失败很可能只是
     * 客户端对一条早已超时的旧 nonce 迟到作答, 而不是密钥真的不配。
     */
    boolean recordAuthFailure() {
        authFailures++;
        return authFailures >= MAX_AUTH_FAILURES || authAttempts >= MAX_AUTH_ATTEMPTS;
    }

    /** 一条已发出、待应答的挑战。 */
    static final class Challenge {
        final String phase;
        final byte[] nonce;
        final long issuedAt;
        /** ENROLL 成功后要消费的注册码 id; <0 表示无 (会话授权登记, 或 AUTH 阶段)。 */
        final long codeId;

        Challenge(String phase, byte[] nonce, long issuedAt, long codeId) {
            this.phase = phase;
            this.nonce = nonce;
            this.issuedAt = issuedAt;
            this.codeId = codeId;
        }
    }
}
