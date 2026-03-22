package com.example.batch.common.logging;

import org.slf4j.MDC;

/**
 * Thin wrapper around SLF4J MDC for consistent structured fields.
 */
public final class BatchMdc {

    private BatchMdc() {
    }

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
