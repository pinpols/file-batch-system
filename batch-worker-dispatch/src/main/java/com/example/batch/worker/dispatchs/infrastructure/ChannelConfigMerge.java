package com.example.batch.worker.dispatchs.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.example.batch.common.utils.Texts;

/** 合并 {@code file_channel_config} 行数据与 {@code config_json}；JSON 中的键会覆盖同名列值。 */
public final class ChannelConfigMerge {

  private static final Set<String> RESERVED_KEYS =
      Set.of(
          "id",
          "tenant_id",
          "tenantId",
          "channel_code",
          "channelCode",
          "channel_type",
          "channelType",
          "dispatch_target",
          "dispatchTarget",
          "enabled",
          "config_json",
          "created_at",
          "updated_at");

  private ChannelConfigMerge() {}

  public static Map<String, Object> merge(Map<String, Object> row, ObjectMapper objectMapper) {
    if (row == null || row.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    row.forEach((k, v) -> out.put(String.valueOf(k), v));
    Object cj = out.get("config_json");
    if (cj == null) {
      return out;
    }
    if (cj instanceof Map<?, ?> m) {
      mergeConfigJson(out, m);
      return out;
    }
    if (objectMapper != null && Texts.hasText(String.valueOf(cj))) {
      try {
        Map<String, Object> parsed =
            objectMapper.readValue(String.valueOf(cj), new TypeReference<>() {});
        mergeConfigJson(out, parsed);
      } catch (Exception ignored) {
      }
    }
    return out;
  }

  private static void mergeConfigJson(Map<String, Object> target, Map<?, ?> parsed) {
    for (Map.Entry<?, ?> entry : parsed.entrySet()) {
      String key = String.valueOf(entry.getKey());
      if (RESERVED_KEYS.contains(key)) {
        continue;
      }
      target.put(key, entry.getValue());
    }
  }
}
