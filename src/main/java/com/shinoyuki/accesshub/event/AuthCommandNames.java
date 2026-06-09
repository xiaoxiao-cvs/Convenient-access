package com.shinoyuki.accesshub.event;

import java.util.Set;

/**
 * 未认证玩家可放行的命令根字面量集合.
 *
 * 单一事实源: PlayerAuthListener (命令拦截) 与 AuthCommand (命令注册) 共用,
 * 避免两处硬编码不一致导致放行漏洞或登录命令被自己拦死。
 */
public final class AuthCommandNames {

    /** 登录类命令根名 (含短别名). 其余命令对未认证玩家全部取消。 */
    public static final Set<String> ALLOWED = Set.of(
            "login", "l",
            "register", "reg",
            "changepassword"
    );

    private AuthCommandNames() {}

    public static boolean isAllowed(String rootLiteral) {
        return rootLiteral != null && ALLOWED.contains(rootLiteral);
    }
}
