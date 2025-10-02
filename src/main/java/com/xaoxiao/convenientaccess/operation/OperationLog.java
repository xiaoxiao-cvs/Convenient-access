package com.xaoxiao.convenientaccess.operation;

import java.time.LocalDateTime;

/**
 * 操作日志实体类
 */
public class OperationLog {
    private Long id;
    private String operationType;  // ADD, REMOVE, QUERY, BATCH_ADD, BATCH_REMOVE, SYNC
    private String targetUuid;
    private String targetName;
    private String operatorIp;
    private String operatorAgent;
    private String requestData;
    private Integer responseStatus;
    private Long executionTime;  // 毫秒
    private LocalDateTime createdAt;
    
    public OperationLog() {
    }
    
    public OperationLog(String operationType, String targetUuid, String targetName,
                       String operatorIp, String operatorAgent, String requestData,
                       Integer responseStatus, Long executionTime) {
        this.operationType = operationType;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.operatorIp = operatorIp;
        this.operatorAgent = operatorAgent;
        this.requestData = requestData;
        this.responseStatus = responseStatus;
        this.executionTime = executionTime;
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getOperationType() {
        return operationType;
    }
    
    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }
    
    public String getTargetUuid() {
        return targetUuid;
    }
    
    public void setTargetUuid(String targetUuid) {
        this.targetUuid = targetUuid;
    }
    
    public String getTargetName() {
        return targetName;
    }
    
    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }
    
    public String getOperatorIp() {
        return operatorIp;
    }
    
    public void setOperatorIp(String operatorIp) {
        this.operatorIp = operatorIp;
    }
    
    public String getOperatorAgent() {
        return operatorAgent;
    }
    
    public void setOperatorAgent(String operatorAgent) {
        this.operatorAgent = operatorAgent;
    }
    
    public String getRequestData() {
        return requestData;
    }
    
    public void setRequestData(String requestData) {
        this.requestData = requestData;
    }
    
    public Integer getResponseStatus() {
        return responseStatus;
    }
    
    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }
    
    public Long getExecutionTime() {
        return executionTime;
    }
    
    public void setExecutionTime(Long executionTime) {
        this.executionTime = executionTime;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    @Override
    public String toString() {
        return "OperationLog{" +
                "id=" + id +
                ", operationType='" + operationType + '\'' +
                ", targetUuid='" + targetUuid + '\'' +
                ", targetName='" + targetName + '\'' +
                ", operatorIp='" + operatorIp + '\'' +
                ", responseStatus=" + responseStatus +
                ", executionTime=" + executionTime +
                ", createdAt=" + createdAt +
                '}';
    }
}
