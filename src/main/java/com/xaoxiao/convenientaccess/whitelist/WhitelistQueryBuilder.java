package com.xaoxiao.convenientaccess.whitelist;

import java.util.ArrayList;
import java.util.List;

/**
 * 白名单查询构建器
 * 支持复杂查询条件的构建
 */
public class WhitelistQueryBuilder {
    private final List<String> conditions = new ArrayList<>();
    private final List<Object> parameters = new ArrayList<>();
    private String orderBy = "created_at DESC";
    private Integer limit;
    private Integer offset;
    
    /**
     * 按玩家名称搜索
     */
    public WhitelistQueryBuilder searchByName(String name) {
        if (name != null && !name.trim().isEmpty()) {
            conditions.add("name LIKE ?");
            parameters.add("%" + name.trim() + "%");
        }
        return this;
    }
    
    /**
     * 按UUID精确匹配
     */
    public WhitelistQueryBuilder filterByUuid(String uuid) {
        if (uuid != null && !uuid.trim().isEmpty()) {
            conditions.add("uuid = ?");
            parameters.add(uuid.trim());
        }
        return this;
    }
    
    /**
     * 按来源类型筛选
     */
    public WhitelistQueryBuilder filterBySource(String source) {
        if (source != null && !source.trim().isEmpty()) {
            conditions.add("source = ?");
            parameters.add(source.trim().toUpperCase());
        }
        return this;
    }
    
    /**
     * 按添加者筛选
     */
    public WhitelistQueryBuilder filterByAddedBy(String addedBy) {
        if (addedBy != null && !addedBy.trim().isEmpty()) {
            conditions.add("added_by_name LIKE ?");
            parameters.add("%" + addedBy.trim() + "%");
        }
        return this;
    }
    
    /**
     * 按活跃状态筛选
     */
    public WhitelistQueryBuilder filterByActive(boolean isActive) {
        conditions.add("is_active = ?");
        parameters.add(isActive);
        return this;
    }
    
    /**
     * 按时间范围筛选
     */
    public WhitelistQueryBuilder filterByDateRange(String startDate, String endDate) {
        if (startDate != null && !startDate.trim().isEmpty()) {
            conditions.add("added_at >= ?");
            parameters.add(startDate);
        }
        if (endDate != null && !endDate.trim().isEmpty()) {
            conditions.add("added_at <= ?");
            parameters.add(endDate);
        }
        return this;
    }
    
    /**
     * 设置排序
     */
    public WhitelistQueryBuilder orderBy(String field, String direction) {
        if (field != null && !field.trim().isEmpty()) {
            String validField = validateSortField(field.trim());
            String validDirection = validateSortDirection(direction);
            this.orderBy = validField + " " + validDirection;
        }
        return this;
    }
    
    /**
     * 设置分页
     */
    public WhitelistQueryBuilder paginate(int page, int size) {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 100) size = 100;
        
        this.limit = size;
        this.offset = (page - 1) * size;
        return this;
    }
    
    /**
     * 构建查询SQL
     */
    public QueryResult build() {
        StringBuilder sql = new StringBuilder("SELECT * FROM whitelist");
        
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(String.join(" AND ", conditions));
        }
        
        sql.append(" ORDER BY ").append(orderBy);
        
        if (limit != null) {
            sql.append(" LIMIT ").append(limit);
            if (offset != null) {
                sql.append(" OFFSET ").append(offset);
            }
        }
        
        return new QueryResult(sql.toString(), parameters);
    }
    
    /**
     * 构建计数查询SQL
     */
    public QueryResult buildCount() {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM whitelist");
        
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(String.join(" AND ", conditions));
        }
        
        return new QueryResult(sql.toString(), parameters);
    }
    
    /**
     * 验证排序字段
     */
    private String validateSortField(String field) {
        switch (field.toLowerCase()) {
            case "name":
                return "name";
            case "uuid":
                return "uuid";
            case "added_by":
            case "added_by_name":
                return "added_by_name";
            case "added_at":
                return "added_at";
            case "source":
                return "source";
            case "created_at":
                return "created_at";
            case "updated_at":
                return "updated_at";
            default:
                return "created_at"; // 默认排序字段
        }
    }
    
    /**
     * 验证排序方向
     */
    private String validateSortDirection(String direction) {
        if (direction != null && "asc".equalsIgnoreCase(direction.trim())) {
            return "ASC";
        }
        return "DESC"; // 默认降序
    }
    
    /**
     * 查询结果类
     */
    public static class QueryResult {
        private final String sql;
        private final List<Object> parameters;
        
        public QueryResult(String sql, List<Object> parameters) {
            this.sql = sql;
            this.parameters = new ArrayList<>(parameters);
        }
        
        public String getSql() {
            return sql;
        }
        
        public List<Object> getParameters() {
            return parameters;
        }
    }
}