package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ResultCode implements DictEnum {
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
  // 登录风控:失败次数达阈值后要求人机验证(验证码),不锁账号——规避 account-lockout DoS。
  // FE 据该 code 弹验证码组件,过验证码后带 captchaToken 重试登录。
  CAPTCHA_REQUIRED("CAPTCHA_REQUIRED", "需要人机验证", "captcha verification required", 401),
  BUSINESS_ERROR("BUSINESS_ERROR", "业务错误", "business error", 422),
  // R-arch-audit-2026-05-23 P1: 替代 QuartzLaunchJob 中 e.getMessage().contains("tenant is suspended")
  // 字符串匹配。租户被运维 / 计费侧暂停后，调度路径需识别该语义并暂停对应 Quartz job 防止日志风暴。
  TENANT_SUSPENDED("TENANT_SUSPENDED", "租户已暂停", "tenant is suspended", 422),
  NOT_IMPLEMENTED("NOT_IMPLEMENTED", "未实现", "not implemented", 501),
  // R-4.1 · 依赖组件短暂不可用（如 Redis 抖动）；表达"稍后重试安全"语义
  SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", "依赖组件暂不可用", "dependency temporarily unavailable", 503),
  // ADR-039 · 凭据 envRef(${ENV_NAME})在部署环境未定义,fail-fast 不静默回落明文
  CREDENTIAL_REF_UNRESOLVED(
      "CREDENTIAL_REF_UNRESOLVED", "凭据引用无法解析", "credential reference unresolved", 400),
  SYSTEM_ERROR("SYSTEM_ERROR", "系统错误", "system error", 500);

  private final String code;
  private final String label;
  private final String defaultMessage;
  private final int httpStatus;
}
