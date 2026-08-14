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
 * 三条设计要点:
 *
 * 1. 挑战必须可重发: 服务端原先只在进服瞬间用 isRemotePresent 判一次客户端有没有本 mod, 而
 *    Connector/兼容层下 PLAY 通道协商可能晚于 PlayerLoggedInEvent 完成 -> 判成"没装"就再也不发,
 *    表现为免密整局不触发。故按 tick 复检并允许最多 MAX_AUTH_ATTEMPTS 次挑战。
 *
 * 2. 允许多条挑战同时在飞: 重发后, 客户端对上一条挑战的迟到应答仍然是合法应答。若只保留最后一个
 *    nonce, 迟到应答会与新 nonce 对不上 -> 被判成"验签失败"误导玩家去打密码, 还顺手把新挑战的
 *    槽位一并消费掉。故保留最近若干条未过期挑战, 应答时逐条试签, 任一条通过即认证。
 *
 * 3. 应答即全部作废: 一次应答取走该阶段所有挑战 (含过期的), 防重放, 也防被反复触发验签运算。
 */
final class DeviceAuthSession {

    /** AUTH 挑战最大发送次数 (含首发). 用尽即放弃免密交回密码登录; 乘以挑战宽限须小于 auth.timeout-seconds。 */
    static final int MAX_AUTH_ATTEMPTS = 3;

    /** 同时保留的未过期挑战上限. 正常路径下不会触顶, 仅防异常路径 (如客户端反复触发登记) 无限增长。 */
    private static final int MAX_LIVE_CHALLENGES = 4;

    /** 已发出、尚未被应答消费的挑战, 队首最旧。 */
    private final Deque<Challenge> live = new ArrayDeque<>();

    private boolean joined;
    private boolean eligible;
    private boolean helloSeen;
    private boolean authDone;
    private int authAttempts;
    private boolean waitingChannelLogged;

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

    /** 免密流程终结 (成功 / 验签失败 / 次数用尽): 不再自动发 AUTH 挑战. /enroll 手动登记不受影响。 */
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

    /** 是否该 (再) 发一次 AUTH 挑战: 进服已处理 + 有资格 + 未终结 + 没有在飞挑战 + 次数未用尽。调用前须先 expire。 */
    boolean canIssueAuth() {
        return joined && eligible && !authDone && live.isEmpty() && authAttempts < MAX_AUTH_ATTEMPTS;
    }

    /** 次数已用尽且无在飞挑战: 调用方落一条放弃日志后 finishAuth。 */
    boolean authAttemptsExhausted() {
        return joined && eligible && !authDone && live.isEmpty() && authAttempts >= MAX_AUTH_ATTEMPTS;
    }

    /** 登记一条已发出的挑战. 返回这是第几次 AUTH 挑战 (ENROLL 不计次, 返回 0)。 */
    int issue(String phase, byte[] nonce, long now, long codeId) {
        if (live.size() >= MAX_LIVE_CHALLENGES) {
            live.pollFirst();
        }
        live.addLast(new Challenge(phase, nonce, now, codeId));
        if (DeviceCrypto.PHASE_AUTH.equals(phase)) {
            return ++authAttempts;
        }
        return 0;
    }

    /**
     * 收到应答: 一次性取走该阶段的全部挑战 (防重放), 只返回其中未过期的候选, 按新到旧排列
     * (新挑战命中概率更高, 让验签尽早短路)。返回空表示没有可用挑战 -> 应答该丢弃。
     */
    List<Challenge> consume(String phase, long now, long ttlMillis) {
        List<Challenge> candidates = new ArrayList<>();
        for (Iterator<Challenge> it = live.iterator(); it.hasNext(); ) {
            Challenge c = it.next();
            if (!c.phase.equals(phase)) {
                continue;
            }
            it.remove();
            if (now - c.issuedAt <= ttlMillis) {
                candidates.add(c);
            }
        }
        Collections.reverse(candidates);
        return candidates;
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
