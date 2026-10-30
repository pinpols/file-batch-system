package io.github.pinpols.batch.console.domain.file.view;

import io.github.pinpols.batch.common.utils.EmptyChecks;
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
    if (EmptyChecks.isNotNull(id)) {
      map.put("id", id);
    }
    if (EmptyChecks.isNotNull(tenantId)) {
      map.put("tenant_id", tenantId);
    }
    if (EmptyChecks.isNotNull(channelCode)) {
      map.put("channel_code", channelCode);
    }
    if (EmptyChecks.isNotNull(channelName)) {
      map.put("channel_name", channelName);
    }
    if (EmptyChecks.isNotNull(channelType)) {
      map.put("channel_type", channelType);
    }
    if (EmptyChecks.isNotNull(targetEndpoint)) {
      map.put("target_endpoint", targetEndpoint);
    }
    if (EmptyChecks.isNotNull(authType)) {
      map.put("auth_type", authType);
    }
    if (EmptyChecks.isNotNull(configJson)) {
      map.put("config_json", configJson);
    }
    if (EmptyChecks.isNotNull(receiptPolicy)) {
      map.put("receipt_policy", receiptPolicy);
    }
    if (EmptyChecks.isNotNull(timeoutSeconds)) {
      map.put("timeout_seconds", timeoutSeconds);
    }
    if (EmptyChecks.isNotNull(enabled)) {
      map.put("enabled", enabled);
    }
    if (EmptyChecks.isNotNull(createdAt)) {
      map.put("created_at", createdAt);
    }
    if (EmptyChecks.isNotNull(updatedAt)) {
      map.put("updated_at", updatedAt);
    }
    return map;
  }
}
