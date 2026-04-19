package com.example.batch.common.enums;

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
  BUSINESS_ERROR("BUSINESS_ERROR", "业务错误", "business error", 422),
  NOT_IMPLEMENTED("NOT_IMPLEMENTED", "未实现", "not implemented", 501),
  // R-4.1 · 依赖组件短暂不可用（如 Redis 抖动）；表达"稍后重试安全"语义
  SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", "依赖组件暂不可用", "dependency temporarily unavailable", 503),
  SYSTEM_ERROR("SYSTEM_ERROR", "系统错误", "system error", 500);

  private final String code;
  private final String label;
  private final String defaultMessage;
  private final int httpStatus;
}
