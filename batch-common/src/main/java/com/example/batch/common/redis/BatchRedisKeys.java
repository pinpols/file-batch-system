package com.example.batch.common.redis;

public final class BatchRedisKeys {

    private BatchRedisKeys() {
    }

    public static String rateLimit(String tenantId, String action, long windowStartEpochSecond) {
        return "ratelimit:%s:%s:%d".formatted(safe(tenantId), safe(action), windowStartEpochSecond);
    }

    public static String outboxCircuit() {
        return "circuit:outbox_publish";
    }

    public static String shedLock(String environment, String name) {
        return "shedlock:%s:%s".formatted(safe(environment), safe(name));
    }

    public static String config(String tenantId, String type, String code) {
        return "config:%s:%s:%s".formatted(safe(tenantId), safe(type), safe(code));
    }

    public static String fileGovernanceMetrics(String tenantId) {
        return "metrics:file_governance:%s".formatted(safe(tenantId));
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "_";
        }
        return value.replace(':', '_');
    }
}
