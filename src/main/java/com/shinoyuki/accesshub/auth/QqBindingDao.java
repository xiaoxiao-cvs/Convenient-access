package com.shinoyuki.accesshub.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.shinoyuki.accesshub.database.DatabaseManager;

/**
 * QQ 号与管理员账号绑定关系的数据访问。
 *
 * 绑定是 Bot 侧一切运维命令的授权依据: 命令由哪个 QQ 发出, 就以该 QQ 绑定的管理员身份执行并记账。
 * 因此查询一律连表带出管理员的启用状态, 由调用方在管理员被停用时拒绝执行 —— 停用账号必须
 * 连同其 QQ 通道一起失效, 否则"从面板踢掉一个管理员"是不彻底的。
 */
public final class QqBindingDao {

    private static final String SELECT_JOINED = """
        SELECT b.id, b.admin_id, b.qq, b.bound_at,
               u.username, u.display_name, u.is_active
        FROM admin_qq_bindings b
        JOIN admin_users u ON u.id = b.admin_id
    """;

    private final DatabaseManager databaseManager;

    public QqBindingDao(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * 把 QQ 号认领到指定管理员名下。
     *
     * 竞态由 qq 列的 UNIQUE 约束兜底: 先 INSERT OR IGNORE, 插入行数为 0 说明该 QQ 已有归属,
     * 此时再查一次判断是"重复绑定同一人"(幂等成功) 还是"已被他人占用"(冲突)。
     */
    public CompletableFuture<BindOutcome> bind(long adminId, String qq) {
        return databaseManager.executeTransactionAsync(connection -> {
            String insert = "INSERT OR IGNORE INTO admin_qq_bindings (admin_id, qq, bound_at) VALUES (?, ?, ?)";
            int inserted;
            try (PreparedStatement stmt = connection.prepareStatement(insert)) {
                stmt.setLong(1, adminId);
                stmt.setString(2, qq);
                stmt.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
                inserted = stmt.executeUpdate();
            }

            QqBinding existing = queryByQq(connection, qq);
            if (inserted > 0) {
                return new BindOutcome(BindStatus.BOUND, existing);
            }
            if (existing != null && existing.adminId() == adminId) {
                return new BindOutcome(BindStatus.ALREADY_BOUND, existing);
            }
            return new BindOutcome(BindStatus.CONFLICT, existing);
        });
    }

    /** 按 QQ 号查绑定 (连带管理员信息); 未绑定返回 null。 */
    public CompletableFuture<QqBinding> findByQq(String qq) {
        return databaseManager.executeAsync(connection -> queryByQq(connection, qq));
    }

    /** 解除某 QQ 的绑定; 返回是否确有一条被删除。 */
    public CompletableFuture<Boolean> unbind(String qq) {
        return databaseManager.executeTransactionAsync(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "DELETE FROM admin_qq_bindings WHERE qq = ?")) {
                stmt.setString(1, qq);
                return stmt.executeUpdate() > 0;
            }
        });
    }

    /**
     * 列出某管理员名下已绑定的全部 QQ 号, 按绑定先后。
     * 次序再按 id 兜底: bound_at 只到毫秒, 连续两次绑定可能落在同一时刻, 单靠时间戳排序不稳定。
     */
    public CompletableFuture<List<String>> listQqByAdmin(long adminId) {
        return databaseManager.executeAsync(connection -> {
            List<String> result = new ArrayList<>();
            try (PreparedStatement stmt = connection.prepareStatement(
                    "SELECT qq FROM admin_qq_bindings WHERE admin_id = ? ORDER BY bound_at ASC, id ASC")) {
                stmt.setLong(1, adminId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(rs.getString("qq"));
                    }
                }
            }
            return result;
        });
    }

    private QqBinding queryByQq(Connection connection, String qq) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(SELECT_JOINED + " WHERE b.qq = ?")) {
            stmt.setString(1, qq);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Timestamp boundAt = rs.getTimestamp("bound_at");
                return new QqBinding(
                        rs.getLong("id"),
                        rs.getLong("admin_id"),
                        rs.getString("qq"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getBoolean("is_active"),
                        boundAt == null ? null : boundAt.toLocalDateTime()
                );
            }
        }
    }

    /** 一条绑定关系, 连带其管理员的身份与启用状态。 */
    public record QqBinding(long id, long adminId, String qq, String adminUsername,
                            String adminDisplayName, boolean adminActive, LocalDateTime boundAt) {

        /** 展示优先用昵称, 没设昵称时退回登录名。 */
        public String displayLabel() {
            return adminDisplayName == null || adminDisplayName.isBlank() ? adminUsername : adminDisplayName;
        }
    }

    public enum BindStatus {
        /** 新建绑定成功。 */
        BOUND,
        /** 该 QQ 早已绑定到同一管理员, 视为成功。 */
        ALREADY_BOUND,
        /** 该 QQ 已被其他管理员占用。 */
        CONFLICT
    }

    /** binding 在 CONFLICT 时是占用者的绑定, 其余情况是本次生效的绑定。 */
    public record BindOutcome(BindStatus status, QqBinding binding) {
    }
}
