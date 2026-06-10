package com.shinoyuki.accesshub.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shinoyuki.accesshub.config.AccessHubConfig;

/**
 * 会话级登录失败计数契约 (纯内存, 不碰 DB).
 * 断言"重连重置 + 认证成功清零 + 按 UUID 独立 + null 安全"——
 * 这是"失败 N 次踢下线但不持久锁号、不误伤合法玩家"的基石, 删掉清零逻辑测试必挂。
 */
class PlayerAuthSessionFailureTest {

    private PlayerAuthService auth;

    @BeforeEach
    void setUp() {
        // 计数相关方法仅操作内存 Map/Set, 不触 dao/codeDao/config, 故可传 null + mock。
        auth = new PlayerAuthService(null, null, mock(AccessHubConfig.class));
    }

    @Test
    void countsUpThenResetsOnClearSession() {
        UUID u = UUID.randomUUID();
        assertEquals(0, auth.getSessionFailureCount(u), "初始应为 0");
        assertEquals(1, auth.recordSessionFailure(u));
        assertEquals(2, auth.recordSessionFailure(u));
        assertEquals(2, auth.getSessionFailureCount(u));

        auth.clearSession(u); // 退服/被踢 -> 重连重置
        assertEquals(0, auth.getSessionFailureCount(u), "clearSession 后应清零");
        assertEquals(1, auth.recordSessionFailure(u), "重连应从 1 重新计数");
    }

    @Test
    void markAuthedClearsCounter() {
        UUID u = UUID.randomUUID();
        auth.recordSessionFailure(u);
        auth.recordSessionFailure(u);
        assertEquals(2, auth.getSessionFailureCount(u));

        auth.markAuthed(u); // 认证成功
        assertTrue(auth.isAuthed(u));
        assertEquals(0, auth.getSessionFailureCount(u), "认证成功应清零失败计数");
    }

    @Test
    void countersAreIndependentPerUuid() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        auth.recordSessionFailure(a);
        auth.recordSessionFailure(a);
        auth.recordSessionFailure(b);
        assertEquals(2, auth.getSessionFailureCount(a));
        assertEquals(1, auth.getSessionFailureCount(b));
    }

    @Test
    void nullUuidIsSafe() {
        assertEquals(0, auth.recordSessionFailure(null));
        assertEquals(0, auth.getSessionFailureCount(null));
        assertFalse(auth.isAuthed(null));
    }
}
