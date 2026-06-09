package com.shinoyuki.accesshub.auth;

import java.time.LocalDateTime;

/**
 * player_auth 表的一行快照. 字段含义见 schema/player_auth.sql.
 *
 * username 已是小写规范化形式 (DAO 写入/查询前统一处理).
 */
public final class PlayerAuthRecord {

    private final String username;
    private final String passwordHash;
    private final LocalDateTime registeredAt;
    private final LocalDateTime lastLoginAt;
    private final String lastLoginIp;
    private final int failCount;
    private final LocalDateTime lockedUntil;

    public PlayerAuthRecord(String username, String passwordHash, LocalDateTime registeredAt,
                            LocalDateTime lastLoginAt, String lastLoginIp,
                            int failCount, LocalDateTime lockedUntil) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.registeredAt = registeredAt;
        this.lastLoginAt = lastLoginAt;
        this.lastLoginIp = lastLoginIp;
        this.failCount = failCount;
        this.lockedUntil = lockedUntil;
    }

    public String getUsername()            { return username; }
    public String getPasswordHash()        { return passwordHash; }
    public LocalDateTime getRegisteredAt() { return registeredAt; }
    public LocalDateTime getLastLoginAt()  { return lastLoginAt; }
    public String getLastLoginIp()         { return lastLoginIp; }
    public int getFailCount()              { return failCount; }
    public LocalDateTime getLockedUntil()  { return lockedUntil; }
}
