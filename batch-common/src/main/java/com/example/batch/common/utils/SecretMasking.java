package com.example.batch.common.utils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * R-4.10：日志 / 审计输出的凭证脱敏。
 *
 * <p>目的：渠道配置（{@code config_json} / {@code channelConfig}）里混杂 username / password / api_key /
 * authorization 等敏感字段。直接 {@code log.debug("config={}", map)} 会把明文打进日志平台、 被 Loki / ELK
 * 汇总转发，形成凭证泄漏通道。
 *
 * <p>约定：字段名按大小写不敏感 contains 匹配下列关键词即视为敏感：
 *
 * <pre>
 *   password / secret / token / api_key / apikey / authorization / private_key / credential
 * </pre>
 *
 * <p>命中 → 值替换为 {@code ****}（无论长度）；调用方负责在日志 / 审计 payload 组装前走这里。
 *
 * <p>该工具不做深递归，默认只处理 map 顶层。嵌套 Map 的敏感字段需调用方递归（避免意外展开 不该展开的数据结构）。
 */
public final class SecretMasking {

  private static final Set<String> SENSITIVE_KEYWORDS =
      Set.of(
          "password",
          "secret",
          "token",
          "api_key",
          "apikey",
          "authorization",
          "private_key",
          "credential");

  private static final String MASK = "****";

  private SecretMasking() {}

  /** 判断 key 名是否敏感（null-safe；大小写不敏感；按 contains 匹配关键词）。 */
  public static boolean isSensitive(String key) {
    if (!Texts.hasText(key)) {
      return false;
    }
    String lower = key.toLowerCase(Locale.ROOT);
    for (String keyword : SENSITIVE_KEYWORDS) {
      if (lower.contains(keyword)) {
        return true;
      }
    }
    return false;
  }

  /** 返回原 map 的拷贝，敏感字段值替换为 {@link #MASK}。null-in → null-out。 */
  public static Map<String, Object> maskSensitiveKeys(Map<String, Object> source) {
    if (source == null) {
      return null;
    }
    Map<String, Object> masked = new LinkedHashMap<>(source.size());
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      if (isSensitive(key) && value != null) {
        masked.put(key, MASK);
      } else {
        masked.put(key, value);
      }
    }
    return masked;
  }
}
