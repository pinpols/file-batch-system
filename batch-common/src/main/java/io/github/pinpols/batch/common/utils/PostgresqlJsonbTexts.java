package io.github.pinpols.batch.common.utils;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;

/**
 * JDBC 驱动可能把 PG {@code json/jsonb} 列映射为 {@code org.postgresql.util.PGobject}；该类仅在运行时存在于 classpath，
 * 编译期不可用（postgresql driver 为 {@code runtime} scope），故用反射取原始 JSON 字符串。
 */
public final class PostgresqlJsonbTexts {

  private static final String PG_OBJECT_CLASS = "org.postgresql.util.PGobject";

  private PostgresqlJsonbTexts() {}

  /**
   * @return PG json/jsonb 的原始 UTF-8 文本；非 PGobject 时返回 {@code null}
   */
  public static String tryExtract(Object raw) {
    if (raw == null) {
      return null;
    }
    if (!PG_OBJECT_CLASS.equals(raw.getClass().getName())) {
      return null;
    }
    try {
      Object v = raw.getClass().getMethod("getValue").invoke(raw);
      return v instanceof String s ? s : null;
    } catch (ReflectiveOperationException ignored) {
      SwallowedExceptionLogger.info(
          PostgresqlJsonbTexts.class, "catch:ReflectiveOperationException", ignored);

      return null;
    }
  }
}
