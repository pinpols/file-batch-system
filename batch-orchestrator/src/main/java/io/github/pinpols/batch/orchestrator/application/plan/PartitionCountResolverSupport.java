package io.github.pinpols.batch.orchestrator.application.plan;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;

/** Shared numeric normalization for partition-count resolvers. */
final class PartitionCountResolverSupport {

  private PartitionCountResolverSupport() {}

  static int firstPositiveInt(Class<?> owner, Object... values) {
    for (Object value : values) {
      int candidate = toInt(owner, value);
      if (candidate > 0) {
        return candidate;
      }
    }
    return 0;
  }

  static long firstPositiveLong(Class<?> owner, Object... values) {
    for (Object value : values) {
      long candidate = toLong(owner, value);
      if (candidate > 0) {
        return candidate;
      }
    }
    return 0L;
  }

  private static int toInt(Class<?> owner, Object value) {
    if (value == null) {
      return 0;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      SwallowedExceptionLogger.info(owner, "catch:NumberFormatException", ignored);
      return 0;
    }
  }

  private static long toLong(Class<?> owner, Object value) {
    if (value == null) {
      return 0L;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      SwallowedExceptionLogger.info(owner, "catch:NumberFormatException", ignored);
      return 0L;
    }
  }
}
