package com.example.batch.common.enums;

public enum ResultCode {
    SUCCESS("SUCCESS", "成功", "success", 200),
    INVALID_ARGUMENT("INVALID_ARGUMENT", "参数非法", "invalid argument", 400),
    VALIDATION_ERROR("VALIDATION_ERROR", "参数校验失败", "validation failed", 400),
    MISSING_IDEMPOTENCY_KEY("MISSING_IDEMPOTENCY_KEY", "缺少幂等键", "idempotency key is required", 400),
    NOT_FOUND("NOT_FOUND", "资源不存在", "resource not found", 404),
    CONFLICT("CONFLICT", "资源冲突", "resource conflict", 409),
    STATE_CONFLICT("STATE_CONFLICT", "状态冲突", "state conflict", 409),
    UNAUTHORIZED("UNAUTHORIZED", "未授权", "unauthorized", 401),
    FORBIDDEN("FORBIDDEN", "禁止访问", "forbidden", 403),
    RATE_LIMITED("RATE_LIMITED", "请求过于频繁", "too many requests", 429),
    BUSINESS_ERROR("BUSINESS_ERROR", "业务错误", "business error", 422),
    NOT_IMPLEMENTED("NOT_IMPLEMENTED", "未实现", "not implemented", 501),
    SYSTEM_ERROR("SYSTEM_ERROR", "系统错误", "system error", 500);

    private final String code;
    private final String label;
    private final String defaultMessage;
    private final int httpStatus;

    ResultCode(String code, String label, String defaultMessage, int httpStatus) {
        this.code = code;
        this.label = label;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public String label() {
        return label;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
