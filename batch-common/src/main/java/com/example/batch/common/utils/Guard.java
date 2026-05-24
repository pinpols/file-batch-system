package com.example.batch.common.utils;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import org.jetbrains.annotations.Contract;

/**
 * 轻量前置校验工具。
 *
 * <p>用途：
 *
 * <ul>
 *   <li>{@link #requireFound}：Mapper 查询后的 NOT_FOUND 断言，消除重复的 null 检查 + 抛异常写法。
 *   <li>{@link #requireText}：Service/Command 层对字符串的非空断言（不替代 @Valid，而是用于 Command 对象）。
 *   <li>{@link #require}：通用业务条件断言。
 * </ul>
 *
 * <p>禁止用 {@code Objects.requireNonNull} 或 Spring {@code Assert}—— 它们抛 {@link
 * IllegalArgumentException}，与项目统一的 {@link BizException} 错误体系不兼容。
 */
public final class Guard {

  private Guard() {}

  /**
   * Mapper 查询结果非 null 断言。
   *
   * <pre>{@code
   * // Before
   * JobInstanceEntity entity = mapper.selectById(tenantId, id);
   * if (entity == null) throw BizException.of(ResultCode.NOT_FOUND, "error.common.not_found_detail", "job instance not found");
   *
   * // After
   * JobInstanceEntity entity = Guard.requireFound(mapper.selectById(tenantId, id), "job instance not found");
   * }</pre>
   */
  @Contract("null, _ -> fail; !null, _ -> param1")
  public static <T> T requireFound(T entity, String message) {
    if (entity == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.common.not_found_detail", message);
    }
    return entity;
  }

  /** 字符串非空断言（用于 Command/内部参数，不替代 Controller 层的 @Valid）。 */
  @Contract("null, _ -> fail")
  public static void requireText(String str, String message) {
    if (!Texts.hasText(str)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument_detail", message);
    }
  }

  /** 通用业务条件断言。 */
  @Contract("false, _ -> fail")
  public static void require(boolean condition, String message) {
    if (!condition) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument_detail", message);
    }
  }

  /**
   * S-1.9：带错误码的通用业务条件断言。业务需要 {@link ResultCode#CONFLICT} / {@link ResultCode#STATE_CONFLICT} /
   * {@link ResultCode#FORBIDDEN} 等非 INVALID_ARGUMENT 语义时， 通过此重载指定，避免绕过工具类直接 throw。
   */
  @Contract("false, _, _ -> fail")
  public static void require(boolean condition, ResultCode resultCode, String message) {
    if (!condition) {
      ResultCode code = resultCode == null ? ResultCode.INVALID_ARGUMENT : resultCode;
      throw BizException.of(code, detailKey(code), message);
    }
  }

  /** S-1.9：带错误码的字符串非空断言。 */
  @Contract("null, _, _ -> fail")
  public static void requireText(String str, ResultCode resultCode, String message) {
    if (!Texts.hasText(str)) {
      ResultCode code = resultCode == null ? ResultCode.INVALID_ARGUMENT : resultCode;
      throw BizException.of(code, detailKey(code), message);
    }
  }

  private static String detailKey(ResultCode code) {
    return switch (code) {
      case NOT_FOUND -> "error.common.not_found_detail";
      case CONFLICT -> "error.common.conflict_detail";
      case STATE_CONFLICT -> "error.common.state_conflict_detail";
      case UNAUTHORIZED -> "error.common.unauthorized_detail";
      case FORBIDDEN -> "error.common.forbidden_detail";
      case RATE_LIMITED -> "error.common.rate_limited_detail";
      case BUSINESS_ERROR -> "error.common.business_error_detail";
      case TENANT_SUSPENDED -> "error.common.business_error_detail";
      case NOT_IMPLEMENTED -> "error.common.not_implemented_detail";
      case SERVICE_UNAVAILABLE -> "error.common.service_unavailable_detail";
      case SYSTEM_ERROR -> "error.common.system_error_detail";
      case INVALID_ARGUMENT -> "error.common.invalid_argument_detail";
      case VALIDATION_ERROR -> "error.common.validation_failed_detail";
      case MISSING_IDEMPOTENCY_KEY -> "error.common.missing_idempotency_key_detail";
      case SUCCESS -> "error.common.success_detail";
    };
  }
}
