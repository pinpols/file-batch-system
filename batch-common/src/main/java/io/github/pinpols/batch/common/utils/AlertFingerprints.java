package io.github.pinpols.batch.common.utils;

/** 用于告警去重的稳定指纹（租户 + 类型 + 资源范围），最长 128 字符。 */
@SuppressWarnings("java:S2583")
public final class AlertFingerprints {

    private AlertFingerprints() {}

    public static String build(String tenantId, String alertType, String resourceKey) {
        String tenant = tenantId == null ? "" : tenantId;
        String type = alertType == null ? "" : alertType;
        String resource = resourceKey == null ? "" : resourceKey;
        String raw = tenant + "|" + type + "|" + resource;
        String fingerprintHex = Hashes.sha256Hex(raw);
        return fingerprintHex.substring(0, 64);
    }
}
