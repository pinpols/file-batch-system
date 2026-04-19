package com.example.batch.common.utils;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Texts;

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
   * if (entity == null) throw new BizException(ResultCode.NOT_FOUND, "job instance not found");
   *
   * // After
   * JobInstanceEntity entity = Guard.requireFound(mapper.selectById(tenantId, id), "job instance not found");
   * }</pre>
   */
  public static <T> T requireFound(T entity, String message) {
    if (entity == null) {
      throw new BizException(ResultCode.NOT_FOUND, message);
    }
    return entity;
  }

  /** 字符串非空断言（用于 Command/内部参数，不替代 Controller 层的 @Valid）。 */
  public static void requireText(String str, String message) {
    if (!Texts.hasText(str)) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, message);
    }
  }

  /** 通用业务条件断言。 */
  public static void require(boolean condition, String message) {
    if (!condition) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, message);
    }
  }

  /**
   * S-1.9：带错误码的通用业务条件断言。业务需要 {@link ResultCode#CONFLICT} /
   * {@link ResultCode#STATE_CONFLICT} / {@link ResultCode#FORBIDDEN} 等非 INVALID_ARGUMENT 语义时，
   * 通过此重载指定，避免绕过工具类直接 throw。
   */
  public static void require(boolean condition, ResultCode resultCode, String message) {
    if (!condition) {
      throw new BizException(
          resultCode == null ? ResultCode.INVALID_ARGUMENT : resultCode, message);
    }
  }

  /** S-1.9：带错误码的字符串非空断言。 */
  public static void requireText(String str, ResultCode resultCode, String message) {
    if (!Texts.hasText(str)) {
      throw new BizException(
          resultCode == null ? ResultCode.INVALID_ARGUMENT : resultCode, message);
    }
  }
}
