package com.xaoxiao.convenientaccess.api;

import com.google.gson.annotations.SerializedName;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * API响应包装类
 */
public class ApiResponse<T> {
    private boolean success;
    private T data;
    private String message;
    private String error;
    private int code;
    @SerializedName("timestamp")
    private String timestamp;
    
    private ApiResponse() {
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
    
    /**
     * 创建成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = true;
        response.data = data;
        response.code = 200;
        return response;
    }
    
    /**
     * 创建成功响应（带消息）
     */
    public static <T> ApiResponse<T> success(T data, String message) {
        ApiResponse<T> response = success(data);
        response.message = message;
        return response;
    }
    
    /**
     * 创建错误响应
     */
    public static <T> ApiResponse<T> error(String error) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = false;
        response.error = error;
        response.code = 500;
        return response;
    }
    
    /**
     * 创建错误响应（带状态码）
     */
    public static <T> ApiResponse<T> error(int code, String error) {
        ApiResponse<T> response = error(error);
        response.code = code;
        return response;
    }
    
    /**
     * 创建客户端错误响应
     */
    public static <T> ApiResponse<T> badRequest(String error) {
        return error(400, error);
    }
    
    /**
     * 创建未授权响应
     */
    public static <T> ApiResponse<T> unauthorized(String error) {
        return error(401, error);
    }
    
    /**
     * 创建禁止访问响应
     */
    public static <T> ApiResponse<T> forbidden(String error) {
        return error(403, error);
    }
    
    /**
     * 创建未找到响应
     */
    public static <T> ApiResponse<T> notFound(String error) {
        return error(404, error);
    }
    
    // Getters
    public boolean isSuccess() {
        return success;
    }
    
    public T getData() {
        return data;
    }
    
    public String getMessage() {
        return message;
    }
    
    public String getError() {
        return error;
    }
    
    public int getCode() {
        return code;
    }
    
    public String getTimestamp() {
        return timestamp;
    }
    
    // Setters
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public void setData(T data) {
        this.data = data;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public void setError(String error) {
        this.error = error;
    }
    
    public void setCode(int code) {
        this.code = code;
    }
    
    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
    
    /**
     * 重写toString方法，返回JSON格式的字符串
     */
    @Override
    public String toString() {
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
            .setPrettyPrinting()
            .create();
        return gson.toJson(this);
    }
}