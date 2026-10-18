package com.cms.bill.common;

import java.time.Instant;

/**
 * 统一响应包装。
 *
 * @param code      业务码，0=成功
 * @param message   提示信息
 * @param data      业务数据
 * @param timestamp 服务器时间
 */
public record ApiResponse<T>(int code, String message, T data, Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data, Instant.now());
    }

    public static ApiResponse<Void> error(int code, String message) {
        return new ApiResponse<>(code, message, null, Instant.now());
    }
}
