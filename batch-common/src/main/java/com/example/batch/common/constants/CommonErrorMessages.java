package com.example.batch.common.constants;

/**
 * 统一错误文案。S2.1:全部中文化(原 12 条英文 + 5 helper),与 messages_zh_CN.properties 默认 zh_CN 输出对齐;真要做 i18n
 * 翻译的接入方应直接抛 {@code BizException.of(code, key, args)} 走 MessageSource,而非读这里的常量(常量是 fail-safe
 * 回退,不能动态切语言)。
 */
public final class CommonErrorMessages {

  public static final String VALIDATION_FAILED = "参数校验失败";
  public static final String INVALID_ARGUMENT = "参数非法";
  public static final String SYSTEM_ERROR = "系统错误";
  public static final String MISSING_IDEMPOTENCY_KEY = "缺少幂等键";
  public static final String AUTHENTICATION_REQUIRED = "需要登录认证";
  public static final String ACCESS_DENIED = "访问被拒绝";
  public static final String INVALID_CONSOLE_JWT = "Console 令牌无效";
  public static final String CONSOLE_JWT_EXPIRED = "Console 令牌已过期";
  public static final String TENANT_REQUIRED = "必须指定租户";
  public static final String TENANT_MISMATCH = "租户不匹配";
  public static final String AI_ASSISTANT_REQUIRES_AUTHENTICATED_USER = "AI 助手需要已登录用户";
  public static final String AI_ASSISTANT_ACCESS_NOT_GRANTED = "未开通 AI 助手访问权限";
  public static final String AI_ASSISTANT_NOT_CONFIGURED = "AI 助手尚未配置";
  public static final String AI_ASSISTANT_DISABLED = "AI 助手已禁用";
  public static final String PROMPT_REQUIRED = "必须提供 prompt";
  public static final String PROMPT_TOO_LONG = "prompt 长度超出限制";
  public static final String PROMPT_VIOLATES_SAFETY_POLICY = "prompt 违反安全策略";
  public static final String PROMPT_OUT_OF_SCOPE = "prompt 超出平台范围";

  private CommonErrorMessages() {}

  public static String fieldTooLong(String fieldName, int maxLength) {
    return fieldName + " 长度超过 " + maxLength;
  }

  public static String fieldRequired(String fieldName) {
    return fieldName + " 不能为空";
  }

  public static String invalidFormat(String fieldName, String expectedFormat) {
    return fieldName
        + " 格式不合法"
        + (expectedFormat == null || expectedFormat.isBlank()
            ? ""
            : "(期望格式 " + expectedFormat + ")");
  }

  public static String invalidValue(String fieldName) {
    return fieldName + " 取值不合法";
  }
}
