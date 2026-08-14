package com.shinoyuki.accesshub.deviceauth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * 免密握手状态机断言测试 (纯逻辑, 时间靠入参注入, 不 sleep 不依赖 MC).
 *
 * 覆盖线上真实故障: 进服瞬间通道未就绪导致整局不挑战; 挑战超时后不重发; 重发时新旧挑战不重叠, 使客户端
 * 卡顿后补上的应答落进真空被误判成验签失败; 验签在飞期间 tick 连发把重试次数烧光。
 *
 * 所有状态都按生产路径构造 (issue -> 推进时间 -> canIssueAuth -> issue), 不直接摆出生产到不了的状态。
 */
class DeviceAuthSessionTest {

    /** 挑战有效期, 对应 auth.device-auth.challenge-timeout-seconds 默认 15 秒。 */
    private static final long TTL = 15_000L;
    /** 重发间隔, 对应 DeviceAuthServer.retryIntervalMillis (有效期的三分之二)。 */
    private static final long RETRY = TTL * 2 / 3;
    private static final long T0 = 1_700_000_000_000L;

    private static byte[] nonce(int seed) {
        byte[] n = new byte[32];
        new Random(seed).nextBytes(n);
        return n;
    }

    /** 走生产路径发一条 AUTH 挑战: 先问 canIssueAuth 再 issue。 */
    private static void issueAuth(DeviceAuthSession s, int seed, long now) {
        assertTrue(s.canIssueAuth(now, RETRY), "生产路径下这一刻应允许发挑战");
        s.issue(DeviceCrypto.PHASE_AUTH, nonce(seed), now, -1L);
    }

    @Test
    void doesNotIssueBeforeJoinEvenAfterHello() {
        DeviceAuthSession s = new DeviceAuthSession();
        assertTrue(s.markHello(), "首个 hello 应被接受");
        assertFalse(s.canIssueAuth(T0, RETRY), "PlayerLoggedInEvent 尚未处理时不得发挑战 (会被随后的 clearSession 抹掉)");

        s.markJoined(true);
        assertTrue(s.canIssueAuth(T0, RETRY), "进服处理完毕且有资格后应可发挑战");
    }

    @Test
    void ineligiblePlayerNeverIssues() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(false); // 没登记设备 / 总开关关闭
        s.markHello();
        assertFalse(s.canIssueAuth(T0, RETRY), "无免密资格的玩家永不发挑战");
        assertFalse(s.authAttemptsExhausted(), "无资格不算次数用尽, 不该刷放弃日志");
    }

    @Test
    void retryIsThrottledByInterval() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        issueAuth(s, 1, T0);

        assertFalse(s.canIssueAuth(T0 + RETRY - 1, RETRY), "重发间隔未到不得重发, 否则几个 tick 就烧光次数");
        assertTrue(s.canIssueAuth(T0 + RETRY, RETRY), "到点应重发, 不必等上一条挑战过期");
    }

    @Test
    void retriedChallengesOverlapSoLateAnswerStillLands() {
        // 核心回归: 重发间隔短于有效期, 新挑战发出时旧挑战仍然有效 -> 客户端卡顿后的迟到应答不会落空
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        issueAuth(s, 1, T0);
        issueAuth(s, 2, T0 + RETRY);

        assertEquals(0, s.expire(T0 + RETRY, TTL), "重发那一刻旧挑战还没过期, 不该被清掉");
        List<DeviceAuthSession.Challenge> candidates = s.candidates(DeviceCrypto.PHASE_AUTH, T0 + RETRY, TTL);
        assertEquals(2, candidates.size(), "重叠期内两条挑战都应作为试签候选");
        assertArrayEquals(nonce(2), candidates.get(0).nonce, "新挑战排前 (命中概率更高, 让验签尽早短路)");
        assertArrayEquals(nonce(1), candidates.get(1).nonce, "旧挑战仍须可用, 否则迟到应答被误判成验签失败");
    }

    @Test
    void expiredChallengeIsNotACandidate() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        issueAuth(s, 1, T0);

        assertEquals(1, s.candidates(DeviceCrypto.PHASE_AUTH, T0 + TTL, TTL).size(), "恰好在宽限内仍算有效");
        assertTrue(s.candidates(DeviceCrypto.PHASE_AUTH, T0 + TTL + 1, TTL).isEmpty(), "超过宽限的 nonce 不得再用于认证");
        assertEquals(1, s.expire(T0 + TTL + 1, TTL), "过期挑战应被清出并报给调用方落日志");
    }

    @Test
    void verifyInFlightBlocksRetryAndSecondResponse() {
        // 验签要查库,  WAL 争锁时可能耗上几秒; 期间若继续重发, 几个 tick 就把 4 次机会烧光
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        issueAuth(s, 1, T0);
        assertTrue(s.beginVerify(), "首个应答应进入验签");

        assertFalse(s.canIssueAuth(T0 + RETRY, RETRY), "验签在飞时不得重发");
        assertFalse(s.beginVerify(), "验签在飞时不得再受理应答 (防灌包空跑 Ed25519)");

        s.endVerify(false);
        assertTrue(s.canIssueAuth(T0 + RETRY, RETRY), "验签结束后重发恢复");
    }

    @Test
    void failedVerifyKeepsChallengesAlive() {
        // 验签不过很可能只是客户端对早已超时的旧 nonce 迟到作答, 此时不能把在飞的新挑战一并作废
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        issueAuth(s, 1, T0);
        s.beginVerify();
        s.endVerify(false);

        assertEquals(1, s.candidates(DeviceCrypto.PHASE_AUTH, T0 + 1, TTL).size(),
                "验签失败不得消费挑战, 客户端还要对它作答");
        assertFalse(s.canIssueAuth(T0 + 1, RETRY), "失败也不该立刻重发, 仍按间隔节流");
    }

    @Test
    void grantedVerifyConsumesEveryChallenge() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        issueAuth(s, 1, T0);
        issueAuth(s, 2, T0 + RETRY);

        s.beginVerify();
        s.endVerify(true);

        assertTrue(s.candidates(DeviceCrypto.PHASE_AUTH, T0 + RETRY + 1, TTL).isEmpty(),
                "验签通过即消费全部挑战: nonce 单次使用, 重放同一签名必须拿不到候选");
        assertFalse(s.canIssueAuth(T0 + 3 * RETRY, RETRY), "已认证的会话不再发挑战");
    }

    @Test
    void failureIsNotFinalWhileRetriesRemain() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        issueAuth(s, 1, T0);
        s.beginVerify();
        s.endVerify(false);

        assertFalse(s.recordAuthFailure(),
                "还有重发机会时验签失败不算定论 (可能是迟到应答), 不该告诉玩家免密失败");
    }

    @Test
    void failureIsFinalOnLastAttempt() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        long now = T0;
        for (int i = 1; i <= DeviceAuthSession.MAX_AUTH_ATTEMPTS; i++) {
            issueAuth(s, i, now);
            now += RETRY;
        }
        s.beginVerify();
        s.endVerify(false);

        assertTrue(s.recordAuthFailure(), "最后一次挑战再失败即为定论, 应告知玩家改用密码");
    }

    @Test
    void repeatedFailuresAreCapped() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        issueAuth(s, 1, T0);

        boolean fin = false;
        for (int i = 0; i < 5; i++) {
            s.beginVerify();
            s.endVerify(false);
            fin = s.recordAuthFailure();
        }
        assertTrue(fin, "同一条挑战被反复灌错应答时必须有上限, 不能无限空跑验签");
    }

    @Test
    void attemptsRunOutAfterMaxRetries() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        long now = T0;
        for (int i = 1; i <= DeviceAuthSession.MAX_AUTH_ATTEMPTS; i++) {
            issueAuth(s, i, now);
            now += RETRY;
        }
        assertFalse(s.canIssueAuth(now, RETRY), "用尽次数后不得再发");
        assertEquals(DeviceAuthSession.MAX_AUTH_ATTEMPTS, s.authAttempts());
        assertFalse(s.authAttemptsExhausted(), "最后一条挑战还没过期时不算用尽, 应答仍可能在路上");

        s.expire(now + TTL + 1, TTL);
        assertTrue(s.authAttemptsExhausted(), "最后一条挑战也过期后才算用尽, 由调用方落一条放弃日志");

        s.finishAuth();
        assertFalse(s.authAttemptsExhausted(), "已终结的会话不应反复报用尽 (否则日志每 tick 刷屏)");
    }

    @Test
    void coverageWindowFitsInsideLoginTimeout() {
        // 覆盖窗口 = (次数-1)*重发间隔 + 有效期, 必须小于 auth.timeout-seconds (默认 60 秒) 才有意义
        long coverage = (DeviceAuthSession.MAX_AUTH_ATTEMPTS - 1) * RETRY + TTL;
        assertTrue(coverage < 60_000L, "免密重试窗口必须留在登录超时之内, 实际 " + coverage + "ms");
        assertTrue(coverage > 30_000L, "窗口太短则整合包慢加载的客户端根本来不及应答, 实际 " + coverage + "ms");
    }

    @Test
    void candidatesIsolatePhases() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        issueAuth(s, 1, T0);
        assertEquals(0, s.issue(DeviceCrypto.PHASE_ENROLL, nonce(2), T0, 77L), "ENROLL 不计入 AUTH 次数");

        List<DeviceAuthSession.Challenge> enroll = s.candidates(DeviceCrypto.PHASE_ENROLL, T0 + 1, TTL);
        assertEquals(1, enroll.size(), "登记应答只能匹配 ENROLL 挑战");
        assertEquals(77L, enroll.get(0).codeId, "注册码 id 须随挑战带到验签成功后才消费");

        assertEquals(1, s.candidates(DeviceCrypto.PHASE_AUTH, T0 + 1, TTL).size(), "AUTH 挑战不受登记流程影响");
        assertEquals(1, s.authAttempts(), "AUTH 计数只统计 AUTH 挑战");
    }

    @Test
    void finishedSessionStopsIssuing() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        s.finishAuth(); // 已确认密钥不配 / 已改用密码登录
        assertFalse(s.canIssueAuth(T0, RETRY), "终结后不得再自动发 AUTH 挑战");
    }

    @Test
    void duplicateHelloIsIgnored() {
        DeviceAuthSession s = new DeviceAuthSession();
        assertTrue(s.markHello());
        assertFalse(s.markHello(), "重复 hello 必须被忽略, 否则客户端可反复触发挑战");
        assertTrue(s.helloSeen(), "已确证客户端装了本 mod 的事实不应被重复 hello 抹掉");
    }

    @Test
    void waitingChannelLogIsRecordedOnce() {
        DeviceAuthSession s = new DeviceAuthSession();
        assertTrue(s.shouldLogWaitingChannel(), "首次等待通道应落一条日志");
        assertFalse(s.shouldLogWaitingChannel(), "按 tick 复检时不得重复刷同一条日志");
    }

    @Test
    void liveChallengesAreBounded() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        // 只有 ENROLL 能被玩家反复触发 (/enroll), 用它压测上限
        for (int i = 0; i < 10; i++) {
            s.issue(DeviceCrypto.PHASE_ENROLL, nonce(i), T0 + i, 10L + i);
        }
        List<DeviceAuthSession.Challenge> candidates = s.candidates(DeviceCrypto.PHASE_ENROLL, T0 + 10, TTL);
        assertEquals(4, candidates.size(), "在飞挑战须有上限, 防被反复触发撑爆内存");
        assertArrayEquals(nonce(9), candidates.get(0).nonce, "保留的应是最近几条");
    }
}
