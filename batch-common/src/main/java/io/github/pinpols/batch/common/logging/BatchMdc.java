package io.github.pinpols.batch.common.logging;

import org.slf4j.MDC;

/** 基于 SLF4J MDC 的轻量封装，统一结构化日志字段写入。 */
public final class BatchMdc {

  private BatchMdc() {}

  public static void put(String key, String value) {
    if (key == null || value == null || value.isBlank()) {
      return;
    }
    MDC.put(key, value);
  }

  public static void putIfAbsent(String key, String value) {
    if (key == null || value == null || value.isBlank()) {
      return;
    }
    if (MDC.get(key) == null) {
      MDC.put(key, value);
    }
  }

  public static void remove(String key) {
    if (key != null) {
      MDC.remove(key);
    }
  }

  /** 批量移除。用于 finally 块一次性清多个字段，替代多行 remove 堆叠。 */
  public static void removeAll(String... keys) {
    if (keys == null) {
      return;
    }
    for (String key : keys) {
      if (key != null) {
        MDC.remove(key);
      }
    }
  }

  public static void clear() {
    MDC.clear();
  }

  public static void withTenantAndTrace(String tenantId, String traceId, Runnable runnable) {
    String prevTenant = MDC.get(StructuredLogField.TENANT_ID);
    String prevTrace = MDC.get(StructuredLogField.TRACE_ID);
    try {
      put(StructuredLogField.TENANT_ID, tenantId);
      put(StructuredLogField.TRACE_ID, traceId);
      runnable.run();
    } finally {
      restore(StructuredLogField.TENANT_ID, prevTenant);
      restore(StructuredLogField.TRACE_ID, prevTrace);
    }
  }

  private static void restore(String key, String previous) {
    if (previous == null) {
      MDC.remove(key);
    } else {
      MDC.put(key, previous);
    }
  }
}
