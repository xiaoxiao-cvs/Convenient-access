package com.xaoxiao.convenientaccess.whitelist;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 白名单条目实体类
 */
public class WhitelistEntry {
    private Long id;
    private String name;
    private String uuid;
    private String addedByName;
    private String addedByUuid;
    private LocalDateTime addedAt;
    private String source;
    private boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    public WhitelistEntry() {}
    
    public WhitelistEntry(String name, String uuid, String addedByName, String addedByUuid, String source) {
        this.name = name;
        this.uuid = uuid;
        this.addedByName = addedByName;
        this.addedByUuid = addedByUuid;
        this.source = source;
        this.isActive = true;
        this.addedAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getUuid() {
        return uuid;
    }
    
    public void setUuid(String uuid) {
        this.uuid = uuid;
    }
    
    public String getAddedByName() {
        return addedByName;
    }
    
    public void setAddedByName(String addedByName) {
        this.addedByName = addedByName;
    }
    
    public String getAddedByUuid() {
        return addedByUuid;
    }
    
    public void setAddedByUuid(String addedByUuid) {
        this.addedByUuid = addedByUuid;
    }
    
    public LocalDateTime getAddedAt() {
        return addedAt;
    }
    
    public void setAddedAt(LocalDateTime addedAt) {
        this.addedAt = addedAt;
    }
    
    public String getSource() {
        return source;
    }
    
    public void setSource(String source) {
        this.source = source;
    }
    
    public boolean isActive() {
        return isActive;
    }
    
    public void setActive(boolean active) {
        isActive = active;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WhitelistEntry that = (WhitelistEntry) o;
        return Objects.equals(uuid, that.uuid);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(uuid);
    }
    
    @Override
    public String toString() {
        return "WhitelistEntry{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", uuid='" + uuid + '\'' +
                ", addedByName='" + addedByName + '\'' +
                ", source='" + source + '\'' +
                ", isActive=" + isActive +
                ", addedAt=" + addedAt +
                '}';
    }
    
    /**
     * 来源类型枚举
     */
    public enum Source {
        PLAYER("PLAYER"),
        ADMIN("ADMIN"),
        SYSTEM("SYSTEM");
        
        private final String value;
        
        Source(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
        
        public static Source fromString(String value) {
            for (Source source : Source.values()) {
                if (source.value.equals(value)) {
                    return source;
                }
            }
            throw new IllegalArgumentException("Unknown source: " + value);
        }
    }
}