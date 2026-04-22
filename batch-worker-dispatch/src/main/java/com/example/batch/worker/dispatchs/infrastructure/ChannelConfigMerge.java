package com.example.batch.worker.dispatchs.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.example.batch.common.utils.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 合并 {@code file_channel_config} 行数据与 {@code config_json}；JSON 中的键会覆盖同名列值。
 *
 * <p><b>S-1.5 · 白名单策略</b>：
 * 之前是黑名单（`RESERVED_KEYS` 挡 id / tenant_id / channel_type ...），漏了 {@code enabled}
 * 跟 {@code receipt_policy} 等控制字段，攻击者可通过 {@code config_json} 绕过管理员设置。
 * 现在改白名单：只有 {@link #ALLOWED_CONFIG_KEYS} 里登记的键才允许从 JSON overlay；
 * 其余键静默忽略并记 WARN 便于审计。
 *
 * <p>新增渠道类型时需同步补充白名单（通过 grep {@code stringProp(channelConfig, "..."} 即可找齐）。
 */
public final class ChannelConfigMerge {

  private static final Logger log = LoggerFactory.getLogger(ChannelConfigMerge.class);

  /**
   * 允许从 {@code config_json} overlay 的键白名单。新增前请确认：
   * <ul>
   *   <li>该键由具体 dispatch adapter 明确消费（grep {@code stringProp(channelConfig, "xxx"}）
   *   <li>非"策略类"字段（如 enabled / receipt_policy）——策略只能走列更新
   * </ul>
   */
  private static final Set<String> ALLOWED_CONFIG_KEYS =
      Set.of(
          // ── 通用 ──
          "target_endpoint",
          // ── SFTP ──
          "sftp_host",
          "sftp_port",
          "sftp_user",
          "sftp_password",
          "sftp_remote_directory",
          "sftp_remote_file_name",
          "sftp_strict_host_key_checking",
          "sftp_known_hosts_path",
          // ── SMTP / EMAIL ──
          "smtp_host",
          "smtp_port",
          "smtp_username",
          "smtp_password",
          "smtp_starttls",
          "mail_from",
          "mail_to",
          "mail_subject",
          // ── OSS ──
          "oss_bucket",
          "oss_object_prefix",
          "oss_object_name",
          // ── NAS ──
          "nas_remote_directory",
          "nas_remote_file_name",
          // ── API / API_PUSH ──
          "api_push_api_key",
          "authorization");

  /**
   * 兼容历史 seed / 手工录入的短键名；merge 时统一折叠为运行时消费的规范键。
   *
   * <p>保留别名兼容而不是继续打 WARN，避免老租户在未迁移存量 config_json 前持续刷屏。
   */
  private static final Map<String, String> KEY_ALIASES =
      Map.of(
          "endpoint", "target_endpoint",
          "bucket", "oss_bucket",
          "prefix", "oss_object_prefix");

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
    Map<String, Object> normalized = new HashMap<>();
    for (Map.Entry<?, ?> entry : parsed.entrySet()) {
      String rawKey = String.valueOf(entry.getKey());
      String key = KEY_ALIASES.getOrDefault(rawKey, rawKey);
      if (!ALLOWED_CONFIG_KEYS.contains(key)) {
        // S-1.5：白名单未登记的键忽略；记 WARN 便于运维发现模板异常或攻击尝试。
        // 尤其要阻止 enabled / receipt_policy 等策略字段被 overlay 覆盖。
        if (log.isWarnEnabled()) {
          log.warn(
              "ChannelConfigMerge ignored non-whitelisted key in config_json: key={}",
              rawKey);
        }
        continue;
      }
      normalized.put(key, entry.getValue());
    }
    normalized.forEach(target::put);
  }
}
