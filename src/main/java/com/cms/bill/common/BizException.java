package com.cms.bill.common;

/**
 * 业务异常，携带 HTTP 状态码，由全局异常处理器统一转换响应。
 */
public class BizException extends RuntimeException {

    private final int httpStatus;

    public BizException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
