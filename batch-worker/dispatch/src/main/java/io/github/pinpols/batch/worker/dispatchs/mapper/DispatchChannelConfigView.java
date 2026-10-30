package io.github.pinpols.batch.worker.dispatchs.mapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 分发渠道的固定列投影；config_json 保留数据库驱动返回值。 */
public record DispatchChannelConfigView(
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
    Instant updatedAt,
    Boolean isDeleted) {

  /** 在渠道插件边界保留原有 snake_case 动态配置形状。 */
  public Map<String, Object> toPluginValues() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("id", id);
    values.put("tenant_id", tenantId);
    values.put("channel_code", channelCode);
    values.put("channel_name", channelName);
    values.put("channel_type", channelType);
    values.put("target_endpoint", targetEndpoint);
    values.put("auth_type", authType);
    values.put("config_json", configJson);
    values.put("receipt_policy", receiptPolicy);
    values.put("timeout_seconds", timeoutSeconds);
    values.put("enabled", enabled);
    values.put("created_at", createdAt);
    values.put("updated_at", updatedAt);
    values.put("is_deleted", isDeleted);
    return values;
  }
}
