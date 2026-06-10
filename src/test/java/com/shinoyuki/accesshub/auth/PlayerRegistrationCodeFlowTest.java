package com.shinoyuki.accesshub.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shinoyuki.accesshub.auth.PlayerAuthService.AuthResult;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.database.DatabaseManager;

/**
 * 注册码端到端校验 (走真 SQLite). 断言离线模式防冒名抢注的核心不变量:
 * 码绑定用户名、一次性消费、过期拒绝、缺码/错码拒绝、弱密码即便有码也拒。
 * 这些断言删掉对应业务逻辑后必挂。
 */
class PlayerRegistrationCodeFlowTest {

    @TempDir
    File tempDir;

    private DatabaseManager db;
    private AccessHubConfig config;
    private PlayerAuthService auth;

    @BeforeEach
    void setUp() throws Exception {
        db = new DatabaseManager(tempDir);
        assertTrue(db.initialize().get(), "数据库初始化应成功");
        config = mock(AccessHubConfig.class);
        when(config.getPlayerAuthMinPasswordLength()).thenReturn(8);
        when(config.isPlayerAuthRejectWeakPassword()).thenReturn(true);
        when(config.getPlayerAuthCodeExpiryMinutes()).thenReturn(1440);
        auth = new PlayerAuthService(new PlayerAuthDao(db), new PlayerRegistrationCodeDao(db), config);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @Test
    void codeBoundToOneNameCannotRegisterAnother() {
        String code = auth.generateRegistrationCode("Alice");
        assertNotNull(code, "生成注册码应返回明文");

        AuthResult asBob = auth.register("Bob", "Str0ngPass", code);
        assertFalse(asBob.isSuccess(), "别人的码不应能注册自己的名字");
        assertTrue(asBob.getMessage().contains("不属于"), "应提示码不属于该用户名: " + asBob.getMessage());

        AuthResult asAlice = auth.register("Alice", "Str0ngPass", code);
        assertTrue(asAlice.isSuccess(), "本人凭绑定码应能注册: " + asAlice.getMessage());
    }

    @Test
    void codeIsConsumedAndCannotBeReused() {
        String code = auth.generateRegistrationCode("Grace");
        assertTrue(auth.register("Grace", "Str0ngPass", code).isSuccess());

        // 删号后用同一码再注册: 码已消费 -> 拒绝 (验证 markUsed 真生效, 非仅靠主键兜底)
        auth.adminReset("Grace");
        AuthResult reuse = auth.register("Grace", "Str0ngPass", code);
        assertFalse(reuse.isSuccess(), "已消费的码删号后不应能再次注册");
        assertTrue(reuse.getMessage().contains("已被使用"), "应提示码已被使用: " + reuse.getMessage());
    }

    @Test
    void alreadyRegisteredNameIsRejected() {
        String code = auth.generateRegistrationCode("Carol");
        assertTrue(auth.register("Carol", "Str0ngPass", code).isSuccess());
        // 同名再注册 (即便另给码) 应被查重拦下
        String code2 = auth.generateRegistrationCode("Carol");
        AuthResult again = auth.register("Carol", "Str0ngPass", code2);
        assertFalse(again.isSuccess(), "已注册用户名不应能再次注册");
    }

    @Test
    void missingOrWrongCodeRejected() {
        AuthResult empty = auth.register("Dave", "Str0ngPass", "");
        assertFalse(empty.isSuccess(), "空码应被拒");

        AuthResult wrong = auth.register("Dave", "Str0ngPass", "WRONGCODE");
        assertFalse(wrong.isSuccess(), "不存在的码应被拒");
        assertTrue(wrong.getMessage().contains("无效"), "应提示注册码无效: " + wrong.getMessage());
    }

    @Test
    void expiredCodeRejected() {
        when(config.getPlayerAuthCodeExpiryMinutes()).thenReturn(-1); // 生成即过期
        String code = auth.generateRegistrationCode("Eve");
        AuthResult r = auth.register("Eve", "Str0ngPass", code);
        assertFalse(r.isSuccess(), "过期码应被拒");
        assertTrue(r.getMessage().contains("过期"), "应提示注册码已过期: " + r.getMessage());
    }

    @Test
    void weakPasswordRejectedEvenWithValidCode() {
        String code = auth.generateRegistrationCode("Frank");
        AuthResult shortPw = auth.register("Frank", "abc", code);
        assertFalse(shortPw.isSuccess(), "短密码应被拒");
        assertTrue(shortPw.getMessage().contains("至少 8"), "应提示至少 8 位: " + shortPw.getMessage());

        String code2 = auth.generateRegistrationCode("Frank");
        AuthResult digits = auth.register("Frank", "12345678", code2);
        assertFalse(digits.isSuccess(), "纯数字弱口令应被拒 (即便够长且有有效码)");
    }

    @Test
    void caseInsensitiveNameBindingMatches() {
        // 码按小写规范化绑定; 玩家名大小写不同也应匹配 (与白名单/认证按名口径一致)
        String code = auth.generateRegistrationCode("MixedCase");
        AuthResult r = auth.register("mIXEDcASE", "Str0ngPass", code);
        assertTrue(r.isSuccess(), "码绑定名应大小写不敏感匹配: " + r.getMessage());
    }
}
