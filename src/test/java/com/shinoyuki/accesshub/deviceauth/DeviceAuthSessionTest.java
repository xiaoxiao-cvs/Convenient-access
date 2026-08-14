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
 * 覆盖线上真实故障: 进服瞬间通道未就绪导致整局不挑战、挑战超时后不重发、重发后客户端对旧 nonce
 * 的迟到应答被误判成验签失败。删掉重发或多挑战并存逻辑, 下面的断言必挂。
 */
class DeviceAuthSessionTest {

    private static final long TTL = 15_000L;
    private static final long T0 = 1_700_000_000_000L;

    private static byte[] nonce(int seed) {
        byte[] n = new byte[32];
        new Random(seed).nextBytes(n);
        return n;
    }

    @Test
    void doesNotIssueBeforeJoinEvenAfterHello() {
        DeviceAuthSession s = new DeviceAuthSession();
        assertTrue(s.markHello(), "首个 hello 应被接受");
        assertFalse(s.canIssueAuth(), "PlayerLoggedInEvent 尚未处理时不得发挑战 (会被随后的 clearSession 抹掉)");

        s.markJoined(true);
        assertTrue(s.canIssueAuth(), "进服处理完毕且有资格后应可发挑战");
    }

    @Test
    void ineligiblePlayerNeverIssues() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(false); // 没登记设备 / 总开关关闭
        s.markHello();
        assertFalse(s.canIssueAuth(), "无免密资格的玩家永不发挑战");
        assertFalse(s.authAttemptsExhausted(), "无资格不算次数用尽, 不该刷放弃日志");
    }

    @Test
    void liveChallengeBlocksDuplicateIssueUntilItExpires() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);

        assertEquals(1, s.issue(DeviceCrypto.PHASE_AUTH, nonce(1), T0, -1L), "首发应为第 1 次");
        assertFalse(s.canIssueAuth(), "已有在飞挑战时不得重复发 (否则新 nonce 会顶掉在飞的那条)");

        assertEquals(0, s.expire(T0 + TTL, TTL), "恰好在宽限内不算超时");
        assertFalse(s.canIssueAuth(), "宽限内仍不得重发");

        assertEquals(1, s.expire(T0 + TTL + 1, TTL), "超过宽限应清出 1 条并报给调用方落日志");
        assertTrue(s.canIssueAuth(), "在飞挑战超时后应允许重发");
    }

    @Test
    void retriesStopAtMaxAttempts() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);

        long now = T0;
        for (int i = 1; i <= DeviceAuthSession.MAX_AUTH_ATTEMPTS; i++) {
            assertTrue(s.canIssueAuth(), "第 " + i + " 次挑战应被允许");
            assertEquals(i, s.issue(DeviceCrypto.PHASE_AUTH, nonce(i), now, -1L), "次数应递增");
            now += TTL + 1;
            s.expire(now, TTL);
        }
        assertFalse(s.canIssueAuth(), "用尽次数后不得再发");
        assertTrue(s.authAttemptsExhausted(), "用尽次数应被识别, 由调用方落一条放弃日志");
        assertEquals(DeviceAuthSession.MAX_AUTH_ATTEMPTS, s.authAttempts());

        s.finishAuth();
        assertFalse(s.authAttemptsExhausted(), "已终结的会话不应反复报用尽 (否则日志每 tick 刷屏)");
    }

    @Test
    void lateResponseToSupersededChallengeStillMatches() {
        // 核心回归: 服务端重发后, 客户端对第一条挑战的迟到应答仍是合法应答
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        byte[] first = nonce(1);
        byte[] second = nonce(2);

        s.issue(DeviceCrypto.PHASE_AUTH, first, T0, -1L);
        s.issue(DeviceCrypto.PHASE_AUTH, second, T0 + 3_000L, -1L);

        List<DeviceAuthSession.Challenge> candidates =
                s.consume(DeviceCrypto.PHASE_AUTH, T0 + 4_000L, TTL);
        assertEquals(2, candidates.size(), "两条挑战都在宽限内, 都应作为候选参与试签");
        assertArrayEquals(second, candidates.get(0).nonce, "新挑战排前 (命中概率更高, 让验签尽早短路)");
        assertArrayEquals(first, candidates.get(1).nonce, "旧挑战仍须保留, 否则迟到应答被误判为验签失败");
    }

    @Test
    void consumeDropsExpiredAndClearsAllForReplayDefence() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        s.issue(DeviceCrypto.PHASE_AUTH, nonce(1), T0, -1L);
        s.issue(DeviceCrypto.PHASE_AUTH, nonce(2), T0 + TTL, -1L);

        List<DeviceAuthSession.Challenge> candidates =
                s.consume(DeviceCrypto.PHASE_AUTH, T0 + TTL + 1, TTL);
        assertEquals(1, candidates.size(), "已超时的那条不得参与试签");
        assertArrayEquals(nonce(2), candidates.get(0).nonce);

        assertTrue(s.consume(DeviceCrypto.PHASE_AUTH, T0 + TTL + 2, TTL).isEmpty(),
                "应答一次即消费全部挑战, 重放同一签名必须拿不到任何候选");
    }

    @Test
    void consumeIsolatesPhases() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        s.issue(DeviceCrypto.PHASE_AUTH, nonce(1), T0, -1L);
        assertEquals(0, s.issue(DeviceCrypto.PHASE_ENROLL, nonce(2), T0, 77L), "ENROLL 不计入 AUTH 次数");

        List<DeviceAuthSession.Challenge> enroll = s.consume(DeviceCrypto.PHASE_ENROLL, T0 + 1, TTL);
        assertEquals(1, enroll.size(), "登记应答只能消费 ENROLL 挑战");
        assertEquals(77L, enroll.get(0).codeId, "注册码 id 须随挑战带到验签成功后才消费");

        assertEquals(1, s.consume(DeviceCrypto.PHASE_AUTH, T0 + 1, TTL).size(),
                "AUTH 挑战不应被登记应答顺手清掉");
        assertEquals(1, s.authAttempts(), "AUTH 计数只统计 AUTH 挑战");
    }

    @Test
    void finishedSessionStopsIssuing() {
        DeviceAuthSession s = new DeviceAuthSession();
        s.markJoined(true);
        s.finishAuth(); // 验签失败: 客户端密钥与库内公钥不配, 重发只会得到同样结果
        assertFalse(s.canIssueAuth(), "终结后不得再自动发 AUTH 挑战");
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
        List<DeviceAuthSession.Challenge> candidates = s.consume(DeviceCrypto.PHASE_ENROLL, T0 + 10, TTL);
        assertEquals(4, candidates.size(), "在飞挑战须有上限, 防被反复触发撑爆内存");
        assertArrayEquals(nonce(9), candidates.get(0).nonce, "保留的应是最近几条");
    }
}
