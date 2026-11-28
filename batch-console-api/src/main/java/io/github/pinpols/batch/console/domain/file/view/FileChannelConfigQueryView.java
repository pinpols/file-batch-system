package io.github.pinpols.batch.console.domain.file.view;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Builder;

/** 文件渠道列表查询的固定列投影；JSONB 配置保持数据库驱动返回值，避免改变复制语义。 */
@Builder
public record FileChannelConfigQueryView(
        Long id,
        String tenantId,
        String channelCode,
        String channelName,
        String channelType,
        String targetEndpoint,
        String authType,
        Object configJson,
        String receiptPolicy,
        Integer timeoutSeconds,
        Boolean enabled,
        Instant createdAt,
        Instant updatedAt) {
    /** 复刻 resultType=map 键集：snake_case、null 值不出键。仅用于 Map 消费边界。 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (id != null) {
            map.put("id", id);
        }
        if (tenantId != null) {
            map.put("tenant_id", tenantId);
        }
        if (channelCode != null) {
            map.put("channel_code", channelCode);
        }
        if (channelName != null) {
            map.put("channel_name", channelName);
        }
        if (channelType != null) {
            map.put("channel_type", channelType);
        }
        if (targetEndpoint != null) {
            map.put("target_endpoint", targetEndpoint);
        }
        if (authType != null) {
            map.put("auth_type", authType);
        }
        if (configJson != null) {
            map.put("config_json", configJson);
        }
        if (receiptPolicy != null) {
            map.put("receipt_policy", receiptPolicy);
        }
        if (timeoutSeconds != null) {
            map.put("timeout_seconds", timeoutSeconds);
        }
        if (enabled != null) {
            map.put("enabled", enabled);
        }
        if (createdAt != null) {
            map.put("created_at", createdAt);
        }
        if (updatedAt != null) {
            map.put("updated_at", updatedAt);
        }
        return map;
    }
}
