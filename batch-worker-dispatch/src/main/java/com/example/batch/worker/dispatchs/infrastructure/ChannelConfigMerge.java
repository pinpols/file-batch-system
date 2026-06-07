package com.example.batch.worker.dispatchs.infrastructure;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.Texts;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * 合并 {@code file_channel_config} 行数据与 {@code config_json}；JSON 中的键会覆盖同名列值。
 *
 * <p><b>S-1.5 · 白名单策略</b>： 之前是黑名单（`RESERVED_KEYS` 挡 id / tenant_id / channel_type ...），漏了 {@code
 * enabled} 跟 {@code receipt_policy} 等控制字段，攻击者可通过 {@code config_json} 绕过管理员设置。 现在改白名单：只有 {@link
 * #ALLOWED_CONFIG_KEYS} 里登记的键才允许从 JSON overlay； 其余键静默忽略并记 WARN 便于审计。
 *
 * <p>新增渠道类型时需同步补充白名单（通过 grep {@code stringProp(channelConfig, "..."} 即可找齐）。
 */
@Slf4j
public final class ChannelConfigMerge {

  /**
   * 允许从 {@code config_json} overlay 的键白名单。新增前请确认：
   *
   * <ul>
   *   <li>该键由具体 dispatch adapter 明确消费（grep {@code stringProp(channelConfig, "xxx"}）
   *   <li>非"策略类"字段（如 enabled / receipt_policy）——策略只能走列更新
   * </ul>
   */
  private static final Set<String> ALLOWED_CONFIG_KEYS =
      Set.of(
          // ── 通用 ──
          "target_endpoint",
          "dispatch_manifest_enabled",
          "dispatch_manifest_suffix",
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
          // ── API / API_PUSH / async receipt polling ──
          "api_push_api_key",
          "authorization",
          "receipt_poll_url");

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

  /**
   * 已是 {@code file_channel_config} 表独立列,JSON 里 redundant 出现时静默忽略,不告警。
   *
   * <p>设计意图:列是策略权威源,JSON overlay 不允许覆盖(S-1.5 安全)。但历史 seed / 老租户配置在 config_json 里 redundant
   * 重复了同名字段(列 + JSON 同时存在,值通常相同),触发原 WARN 持续刷屏(每次 dispatch 都一条, 240 条 / 30min)。
   *
   * <p>本集合标记"已知 redundant 列字段"——既不允许 overlay 覆盖列(列优先,通过 line 119 normalized 不加入这些 key 实现),也不告警。运维
   * audit 时识别真正的策略攻击,不应被这种已知 redundant 噪声淹没。新增控制类列字段时同步加入。
   */
  private static final Set<String> LEGACY_REDUNDANT_KEYS =
      Set.of("receipt_policy", "receipt_polling_window_seconds", "enabled", "channel_type");

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
        SwallowedExceptionLogger.warn(ChannelConfigMerge.class, "catch:Exception", ignored);
      }
    }
    return out;
  }

  private static void mergeConfigJson(Map<String, Object> target, Map<?, ?> parsed) {
    Map<String, Object> normalized = new HashMap<>();
    for (Map.Entry<?, ?> entry : parsed.entrySet()) {
      String rawKey = String.valueOf(entry.getKey());
      String key = KEY_ALIASES.getOrDefault(rawKey, rawKey);
      if (LEGACY_REDUNDANT_KEYS.contains(key)) {
        // 已是表独立列(策略字段),JSON 里 redundant 不告警 — 列保持权威,直接静默跳过
        continue;
      }
      if (!ALLOWED_CONFIG_KEYS.contains(key)) {
        // S-1.5：白名单未登记的键忽略；记 WARN 便于运维发现模板异常或攻击尝试。
        // 尤其要阻止 enabled / receipt_policy 等策略字段被 overlay 覆盖。
        if (log.isWarnEnabled()) {
          log.warn("ChannelConfigMerge ignored non-whitelisted key in config_json: key={}", rawKey);
        }
        continue;
      }
      normalized.put(key, entry.getValue());
    }
    normalized.forEach(target::put);
  }
}
