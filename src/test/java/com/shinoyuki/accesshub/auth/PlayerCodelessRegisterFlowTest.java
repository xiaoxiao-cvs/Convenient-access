package com.shinoyuki.accesshub.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 免码注册流程 (走真 SQLite). 守护"注册码临时停用"期间的行为契约:
 * 不带码可注册并登录、带旧格式的无效码不报错、用户名查重是此时唯一的抢注防线 (含大小写不敏感)、
 * 弱密码策略不受影响。恢复注册码校验后本类必挂 -> 提醒同步删掉本类并解除
 * PlayerRegistrationCodeFlowTest 的 @Disabled。
 */
class PlayerCodelessRegisterFlowTest {

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
        auth = new PlayerAuthService(new PlayerAuthDao(db), new PlayerRegistrationCodeDao(db), config);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @Test
    void registerWithoutCodeSucceedsAndCanLogin() {
        // 两参数 /register 走的就是 code=null 这条路径 (AuthCommand.doRegister)
        AuthResult reg = auth.register("Alice", "Str0ngPass", null);
        assertTrue(reg.isSuccess(), "无码应能注册: " + reg.getMessage());

        AuthResult login = auth.verify("Alice", "Str0ngPass", "127.0.0.1");
        assertTrue(login.isSuccess(), "注册后应能用该密码登录: " + login.getMessage());

        AuthResult wrongPw = auth.verify("Alice", "Wr0ngPass", "127.0.0.1");
        assertFalse(wrongPw.isSuccess(), "错误密码仍应被拒");
        assertTrue(wrongPw.isPasswordMismatch(), "错误密码应归类为密码不匹配 (供命令层计入踢出阈值)");
    }

    @Test
    void strayCodeArgumentIsIgnored() {
        // 旧文案/老玩家仍会敲三参数; 停用期间那个码不参与判定, 不能因此拒绝注册
        AuthResult reg = auth.register("Bob", "Str0ngPass", "ZZZZ-ZZZZ");
        assertTrue(reg.isSuccess(), "带无效码也应注册成功 (码被忽略): " + reg.getMessage());
        assertTrue(auth.verify("Bob", "Str0ngPass", "127.0.0.1").isSuccess(), "带码注册的账号同样能登录");
    }

    @Test
    void duplicateNameRejectedWithoutCodeGuard() {
        // 没有码之后, 用户名查重是唯一的"别人别想覆盖我账号"防线, 必须严守
        assertTrue(auth.register("Carol", "Str0ngPass", null).isSuccess());

        AuthResult again = auth.register("Carol", "An0therPass", null);
        assertFalse(again.isSuccess(), "已注册用户名不应被再次注册覆盖");
        assertTrue(again.getMessage().contains("已注册"), "应提示该账号已注册: " + again.getMessage());
        // 覆盖失败后原密码必须仍然有效 (未被后来者改掉)
        assertTrue(auth.verify("Carol", "Str0ngPass", "127.0.0.1").isSuccess(), "原密码应保持有效");
        assertFalse(auth.verify("Carol", "An0therPass", "127.0.0.1").isSuccess(), "后来者的密码不应生效");
    }

    @Test
    void duplicateCheckIsCaseInsensitive() {
        assertTrue(auth.register("MixedCase", "Str0ngPass", null).isSuccess());

        AuthResult other = auth.register("mIXEDcASE", "An0therPass", null);
        assertFalse(other.isSuccess(), "仅大小写不同的同名不应绕过查重");
        assertTrue(auth.verify("mixedcase", "Str0ngPass", "127.0.0.1").isSuccess(),
                "登录同样按小写口径匹配同一账号");
    }

    @Test
    void weakPasswordStillRejected() {
        AuthResult shortPw = auth.register("Dave", "abc", null);
        assertFalse(shortPw.isSuccess(), "短密码应被拒");
        assertTrue(shortPw.getMessage().contains("至少 8"), "应提示至少 8 位: " + shortPw.getMessage());

        AuthResult digits = auth.register("Dave", "12345678", null);
        assertFalse(digits.isSuccess(), "纯数字弱口令应被拒");

        // 弱密码被拒后不得留下半个账号
        assertFalse(auth.isRegistered("Dave"), "被拒的注册不应写入账号");
    }
}
