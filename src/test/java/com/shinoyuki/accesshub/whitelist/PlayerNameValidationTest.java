package com.shinoyuki.accesshub.whitelist;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 玩家名格式校验的边界 (3-16 位字母/数字/下划线)。
 *
 * 该规则原为 WhitelistManager 的私有方法, 而 API 层另有一套 1-64 字符的宽松校验:
 * 非法名字能穿过 Controller, 到 manager 才被静默判 false, 与"玩家已存在"共用同一条 409
 * 分支, 最终回给调用方"玩家已在白名单中或添加失败"—— 与实情无关的误导性文案。
 * 现规则收敛为唯一的公开实现, 由 Controller 前置调用并回 400。
 *
 * 若把可见性改回 private 或放宽规则, 本类用例必挂。
 */
class PlayerNameValidationTest {

    @Test
    void acceptsLegalNames() {
        assertTrue(WhitelistManager.isValidPlayerName("abc"), "3 位是长度下界");
        assertTrue(WhitelistManager.isValidPlayerName("Notch"), "常规玩家名");
        assertTrue(WhitelistManager.isValidPlayerName("a_1234567890_bcd"), "16 位是长度上界");
        assertTrue(WhitelistManager.isValidPlayerName("___"), "纯下划线在字符集内");
        assertTrue(WhitelistManager.isValidPlayerName("xinglongge"), "生产库中的真实玩家名");
    }

    @Test
    void rejectsOutOfRangeLength() {
        assertFalse(WhitelistManager.isValidPlayerName("ab"), "2 位低于下界");
        assertFalse(WhitelistManager.isValidPlayerName("a".repeat(17)), "17 位超出上界");
        assertFalse(WhitelistManager.isValidPlayerName(""), "空串应被拒");
    }

    @Test
    void rejectsIllegalCharacters() {
        assertFalse(WhitelistManager.isValidPlayerName("player name"), "空格不在字符集内");
        assertFalse(WhitelistManager.isValidPlayerName("玩家名字"), "非 ASCII 应被拒");
        assertFalse(WhitelistManager.isValidPlayerName("player-name"), "连字符不在字符集内");
        assertFalse(WhitelistManager.isValidPlayerName("a'; DROP TABLE whitelist;--"), "注入样式应被拒");
        assertFalse(WhitelistManager.isValidPlayerName(" abc"), "前导空格应被拒");
        assertFalse(WhitelistManager.isValidPlayerName("abc "), "尾随空格应被拒");
    }

    @Test
    void rejectsNull() {
        assertFalse(WhitelistManager.isValidPlayerName(null), "null 应被拒而不是抛异常");
    }
}
