package io.github.pinpols.batch.common.utils;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

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
 * <p>Map 和集合中的敏感字段会递归脱敏；非 Map/集合值保持原样。
 */
@SuppressWarnings("java:S2583")
public final class SecretMasking {

  private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
      "password",
      "secret",
      "token",
      "api_key",
      "apikey",
      "authorization",
      "private_key",
      "credential");

  public static final String MASKED_VALUE = "****";

  private SecretMasking() {}

  /** 判断 key 名是否敏感（null-safe；大小写不敏感；按 contains 匹配关键词）。 */
  public static boolean isSensitive(@Nullable String key) {
    if (key == null || key.isBlank()) { // empty-check: allow - Sonar S2259
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

  /** 返回原 map 的拷贝，敏感字段值替换为 {@link #MASKED_VALUE}。null-in → null-out。 */
  @Nullable
  public static Map<String, Object> maskSensitiveKeys(@Nullable Map<String, Object> source) {
    return maskSensitiveKeys(source, Set.of());
  }

  /** 返回原 map 的拷贝，并将额外指定的字段按敏感值处理。 */
  @Nullable
  public static Map<String, Object> maskSensitiveKeys(
      @Nullable Map<String, Object> source, Set<String> additionalSensitiveKeys) {
    if (source == null) {
      return null;
    }
    Map<String, Object> masked = new LinkedHashMap<>(source.size());
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      if (isSensitive(key, additionalSensitiveKeys) && EmptyChecks.isNotNull(value)) {
        masked.put(key, MASKED_VALUE);
      } else {
        masked.put(key, maskNestedValue(value, additionalSensitiveKeys));
      }
    }
    return masked;
  }

  /** 对动态 JSON 做递归凭据脱敏；无效 JSON 由调用方决定如何降级处理。 */
  @Nullable
  public static String maskSensitiveJson(@Nullable String json) {
    return maskSensitiveJson(json, Set.of());
  }

  /** 对动态 JSON 做递归凭据脱敏，并将额外指定的字段按敏感值处理。 */
  @Nullable
  public static String maskSensitiveJson(
      @Nullable String json, Set<String> additionalSensitiveKeys) {
    if (json == null || json.isBlank()) { // empty-check: allow - Sonar S2259
      return json;
    }
    return JsonUtils.toJson(
        maskNestedValue(JsonUtils.fromJson(json, Object.class), additionalSensitiveKeys));
  }

  /** 判断动态 JSON 是否仍含有未恢复的敏感字段掩码。 */
  public static boolean containsMaskedSensitiveValue(@Nullable String json) {
    if (json == null || json.isBlank()) { // empty-check: allow - Sonar S2259
      return false;
    }
    return containsMaskedSensitiveValue(JsonUtils.fromJson(json, Object.class));
  }

  /** 从跨租户配置副本中递归移除敏感字段，避免复制源环境凭据或租户级密钥引用。 */
  @Nullable
  public static String removeSensitiveJsonFields(@Nullable String json) {
    if (json == null || json.isBlank()) { // empty-check: allow - Sonar S2259
      return json;
    }
    return JsonUtils.toJson(removeSensitiveFields(JsonUtils.fromJson(json, Object.class)));
  }

  /** 更新请求回传脱敏 JSON 时，从当前数据库值恢复相同位置的掩码凭据。 */
  @Nullable
  public static String preserveMaskedJson(
      @Nullable String submittedJson, @Nullable String storedJson) {
    return preserveMaskedJson(submittedJson, storedJson, Set.of());
  }

  /** 更新回传脱敏 JSON 时，从当前数据库值恢复敏感键和额外指定字段的掩码值。 */
  @Nullable
  public static String preserveMaskedJson(
      @Nullable String submittedJson,
      @Nullable String storedJson,
      Set<String> additionalSensitiveKeys) {
    if (submittedJson == null // empty-check: allow - Sonar S2259
        || submittedJson.isBlank() // empty-check: allow - Sonar S2259
        || storedJson == null // empty-check: allow - Sonar S2259
        || storedJson.isBlank()) { // empty-check: allow - Sonar S2259
      return submittedJson;
    }
    Object submitted = JsonUtils.fromJson(submittedJson, Object.class);
    Object stored = JsonUtils.fromJson(storedJson, Object.class);
    return JsonUtils.toJson(restoreMaskedValue(submitted, stored, additionalSensitiveKeys));
  }

  /** 脱敏 URI 中的 userinfo 和敏感 query 参数；普通文件路径及非敏感 URI 保持不变。 */
  @Nullable
  public static String maskEndpoint(@Nullable String endpoint) {
    if (endpoint == null || endpoint.isBlank()) { // empty-check: allow - Sonar S2259
      return endpoint;
    }
    return transformEndpoint(endpoint, null, false);
  }

  /** 更新回传已脱敏端点时，从存量端点恢复 userinfo 和对应 query 凭据。 */
  @Nullable
  public static String preserveMaskedEndpoint(@Nullable String submitted, @Nullable String stored) {
    if (submitted == null || submitted.isBlank()) { // empty-check: allow - Sonar S2259
      return submitted;
    }
    return transformEndpoint(submitted, stored, true);
  }

  @Nullable
  private static String transformEndpoint(
      @Nullable String endpoint, @Nullable String storedEndpoint, boolean restore) {
    if (endpoint == null || endpoint.isBlank()) { // empty-check: allow - Sonar S2259
      return endpoint;
    }
    try {
      URI uri = URI.create(endpoint);
      if (EmptyChecks.isNull(uri.getScheme()) || uri.isOpaque()) {
        return endpoint;
      }
      URI storedUri = restore
              && storedEndpoint != null // empty-check: allow - Sonar S2259
              && !storedEndpoint.isBlank() // empty-check: allow - Sonar S2259
          ? URI.create(storedEndpoint)
          : null;
      String authority = uri.getRawAuthority();
      if (EmptyChecks.isNotNull(uri.getRawUserInfo())) {
        String userInfo = uri.getRawUserInfo();
        if (restore
            && MASKED_VALUE.equals(userInfo)
            && storedUri != null) { // empty-check: allow - Sonar S2259
          userInfo = storedUri.getRawUserInfo();
        } else if (!restore) {
          userInfo = MASKED_VALUE;
        }
        int at = authority.lastIndexOf('@');
        authority = (EmptyChecks.isNull(userInfo) ? "" : userInfo) + authority.substring(at);
      }
      String query = transformQuery(
          uri.getRawQuery(),
          storedUri == null // empty-check: allow - Sonar S2259
              ? null
              : storedUri.getRawQuery(),
          restore);
      return renderUri(uri, authority, query);
    } catch (IllegalArgumentException ignored) {
      return endpoint.matches("(?i)^[a-z][a-z0-9+.-]*://.*") ? MASKED_VALUE : endpoint;
    }
  }

  @Nullable
  private static String transformQuery(
      @Nullable String query, @Nullable String storedQuery, boolean restore) {
    if (EmptyChecks.isNull(query)) {
      return null;
    }
    String[] parameters = query.split("&", -1);
    String[] storedParameters =
        EmptyChecks.isNull(storedQuery) ? new String[0] : storedQuery.split("&", -1);
    for (int index = 0; index < parameters.length; index++) {
      int equals = parameters[index].indexOf('=');
      String rawName = equals < 0 ? parameters[index] : parameters[index].substring(0, equals);
      String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
      if (!isSensitive(name) && !"key".equalsIgnoreCase(name)) {
        continue;
      }
      if (equals < 0) {
        parameters[index] = rawName + "=" + MASKED_VALUE;
      } else {
        String value = parameters[index].substring(equals + 1);
        if (restore && MASKED_VALUE.equals(value)) {
          String storedValue = queryValue(storedParameters, name);
          if (EmptyChecks.isNotNull(storedValue)) {
            value = storedValue;
          }
        } else if (!restore) {
          value = MASKED_VALUE;
        }
        parameters[index] = rawName + "=" + value;
      }
    }
    return String.join("&", parameters);
  }

  @Nullable
  private static String queryValue(String[] parameters, String expectedName) {
    for (String parameter : parameters) {
      int equals = parameter.indexOf('=');
      if (equals < 0) {
        continue;
      }
      String name = URLDecoder.decode(parameter.substring(0, equals), StandardCharsets.UTF_8);
      if (name.equalsIgnoreCase(expectedName)) {
        return parameter.substring(equals + 1);
      }
    }
    return null;
  }

  private static String renderUri(URI uri, String authority, @Nullable String query) {
    StringBuilder rendered = new StringBuilder(uri.getScheme()).append(':');
    if (EmptyChecks.isNotNull(authority)) {
      rendered.append("//").append(authority);
    }
    if (EmptyChecks.isNotNull(uri.getRawPath())) {
      rendered.append(uri.getRawPath());
    }
    if (EmptyChecks.isNotNull(query)) {
      rendered.append('?').append(query);
    }
    if (EmptyChecks.isNotNull(uri.getRawFragment())) {
      rendered.append('#').append(uri.getRawFragment());
    }
    return rendered.toString();
  }

  /** 对动态 JSON 值递归脱敏；保留 Map/List 类型供既有响应契约使用。 */
  @Nullable
  public static Object maskSensitiveValue(
      @Nullable Object value, Set<String> additionalSensitiveKeys) {
    return maskNestedValue(value, additionalSensitiveKeys);
  }

  private static boolean isSensitive(@Nullable String key, Set<String> additionalSensitiveKeys) {
    return isSensitive(key)
        || additionalSensitiveKeys.stream()
            .anyMatch(candidate -> candidate != null // empty-check: allow - Sonar S2259
                && candidate.equalsIgnoreCase(key));
  }

  @Nullable
  private static Object maskNestedValue(
      @Nullable Object value, Set<String> additionalSensitiveKeys) {
    if (value instanceof Map<?, ?> source) {
      Map<String, Object> masked = new LinkedHashMap<>(source.size());
      for (Map.Entry<?, ?> entry : source.entrySet()) {
        String key = String.valueOf(entry.getKey());
        Object nested = entry.getValue();
        masked.put(
            key,
            isSensitive(key, additionalSensitiveKeys) && EmptyChecks.isNotNull(nested)
                ? MASKED_VALUE
                : maskNestedValue(nested, additionalSensitiveKeys));
      }
      return masked;
    }
    if (value instanceof Iterable<?> source) {
      List<Object> masked = new ArrayList<>();
      for (Object nested : source) {
        masked.add(maskNestedValue(nested, additionalSensitiveKeys));
      }
      return masked;
    }
    return value;
  }

  @Nullable
  private static Object restoreMaskedValue(
      @Nullable Object submitted, @Nullable Object stored, Set<String> additionalSensitiveKeys) {
    if (submitted instanceof Map<?, ?> submittedMap) {
      Map<?, ?> storedMap = stored instanceof Map<?, ?> map ? map : Map.of();
      Map<String, Object> restored = new LinkedHashMap<>(submittedMap.size());
      for (Map.Entry<?, ?> entry : submittedMap.entrySet()) {
        String key = String.valueOf(entry.getKey());
        Object value = entry.getValue();
        if (isSensitive(key, additionalSensitiveKeys)
            && MASKED_VALUE.equals(value)
            && storedMap.containsKey(key)) {
          restored.put(key, storedMap.get(key));
        } else {
          restored.put(key, restoreMaskedValue(value, storedMap.get(key), additionalSensitiveKeys));
        }
      }
      return restored;
    }
    if (submitted instanceof List<?> submittedList) {
      List<?> storedList = stored instanceof List<?> list ? list : List.of();
      List<Object> restored = new ArrayList<>(submittedList.size());
      for (int index = 0; index < submittedList.size(); index++) {
        Object storedValue = index < storedList.size() ? storedList.get(index) : null;
        restored.add(
            restoreMaskedValue(submittedList.get(index), storedValue, additionalSensitiveKeys));
      }
      return restored;
    }
    return submitted;
  }

  private static boolean containsMaskedSensitiveValue(@Nullable Object value) {
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = String.valueOf(entry.getKey());
        Object nested = entry.getValue();
        if (isSensitive(key) && MASKED_VALUE.equals(nested)) {
          return true;
        }
        if (containsMaskedSensitiveValue(nested)) {
          return true;
        }
      }
    } else if (value instanceof Iterable<?> values) {
      for (Object nested : values) {
        if (containsMaskedSensitiveValue(nested)) {
          return true;
        }
      }
    }
    return false;
  }

  @Nullable
  private static Object removeSensitiveFields(@Nullable Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sanitized = new LinkedHashMap<>(map.size());
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = String.valueOf(entry.getKey());
        if (!isSensitive(key)) {
          sanitized.put(key, removeSensitiveFields(entry.getValue()));
        }
      }
      return sanitized;
    }
    if (value instanceof Iterable<?> values) {
      List<Object> sanitized = new ArrayList<>();
      for (Object nested : values) {
        sanitized.add(removeSensitiveFields(nested));
      }
      return sanitized;
    }
    return value;
  }
}
