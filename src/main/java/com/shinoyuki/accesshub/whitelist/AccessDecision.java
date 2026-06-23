package com.shinoyuki.accesshub.whitelist;

/**
 * 进服访问决策三态。
 *
 * 取代原先 isPlayerWhitelistedOffline 的布尔判定: 布尔无法区分"压根不在白名单"与
 * "在白名单但被管理员手动禁用 (is_active=0)", 导致后者也只能复用"未在白名单"踢出文案。
 * 拆出 DISABLED 后, 进服监听器可向被禁用玩家展示专属提示。
 */
public enum AccessDecision {
    /** 在白名单且 is_active=1, 放行。 */
    ALLOWED,
    /** 在白名单但 is_active=0 (管理员手动关闭访问权限)。 */
    DISABLED,
    /** 不在白名单。 */
    NOT_WHITELISTED
}
