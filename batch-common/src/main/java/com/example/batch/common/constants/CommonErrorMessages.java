package com.example.batch.common.constants;

public final class CommonErrorMessages {

    public static final String VALIDATION_FAILED = "参数校验失败";
    public static final String INVALID_ARGUMENT = "参数非法";
    public static final String SYSTEM_ERROR = "系统错误";
    public static final String MISSING_IDEMPOTENCY_KEY = "缺少幂等键";
    public static final String AUTHENTICATION_REQUIRED = "authentication required";
    public static final String ACCESS_DENIED = "access denied";
    public static final String INVALID_CONSOLE_TOKEN = "invalid console token";
    public static final String INVALID_CONSOLE_JWT = "invalid console token";
    public static final String CONSOLE_JWT_EXPIRED = "console token expired";
    public static final String TENANT_REQUIRED = "tenant is required";
    public static final String TENANT_MISMATCH = "tenant mismatch";
    public static final String AI_ASSISTANT_REQUIRES_AUTHENTICATED_USER = "ai assistant requires authenticated user";
    public static final String AI_ASSISTANT_ACCESS_NOT_GRANTED = "ai assistant access is not granted";
    public static final String AI_ASSISTANT_NOT_CONFIGURED = "ai assistant is not configured";
    public static final String AI_ASSISTANT_DISABLED = "ai assistant is disabled";
    public static final String PROMPT_REQUIRED = "prompt is required";
    public static final String PROMPT_TOO_LONG = "prompt is too long";
    public static final String PROMPT_VIOLATES_SAFETY_POLICY = "prompt violates safety policy";
    public static final String PROMPT_OUT_OF_SCOPE = "prompt is outside the platform scope";

    private CommonErrorMessages() {
    }

    public static String fieldTooLong(String fieldName, int maxLength) {
        return fieldName + " too long (max " + maxLength + ")";
    }

    public static String fieldRequired(String fieldName) {
        return fieldName + " is required";
    }

    public static String invalidFormat(String fieldName, String expectedFormat) {
        return fieldName + " format invalid" + (expectedFormat == null || expectedFormat.isBlank()
                ? ""
                : " (expected " + expectedFormat + ")");
    }

    public static String invalidValue(String fieldName) {
        return fieldName + " is invalid";
    }
}
