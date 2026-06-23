package io.github.pinpols.batch.common.security;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lane C — 凭据字段静态拒入闸门。
 *
 * <p>用途:Worker 收到 task parameters / SDK 上报 descriptor / orchestrator 接收 heartbeat 时,递归扫描 Map key,
 * 一旦命中高风险关键字(password / secret / apiKey / token / credential / accessKey / privateKey /
 * clientSecret), 即抛 {@link BizException} 拒入。强制凭据走环境变量,不再落入 DB / 日志 / Kafka payload。
 *
 * <p>命中策略:key 转小写后做"包含"匹配(子串足够 → 例如 {@code db_password} / {@code my-api-key} 都会被拦),大小写不敏感。 同时下钻嵌套
 * {@link Map}(对 {@code defaults} / {@code inputSchema} 这种嵌套结构有效)。
 *
 * <p><b>已知误报风险</b>:像 {@code password_must_change} / {@code token_expiry} 这种"非凭据本身但名字含关键词"
 * 的字段也会被拦。当前策略偏 fail-closed:宁可让调用方重命名(改 {@code mustChangePassword} 字段名亦无效,内部还是 contains
 * "password"),也不放行真凭据。后续如需放行白名单,在 {@link #SENSITIVE_KEYWORDS} 旁补 allowlist。
 */
public final class SensitiveDataValidator {

  /** 命中即拒的关键字(全部小写,匹配规则:key.toLowerCase().contains(keyword))。 */
  static final List<String> SENSITIVE_KEYWORDS =
      List.of(
          "password",
          "passwd",
          "secret",
          "apikey",
          "api_key",
          "token",
          "credential",
          "accesskey",
          "access_key",
          "privatekey",
          "private_key",
          "clientsecret",
          "client_secret");

  private SensitiveDataValidator() {}

  /**
   * 扫描 {@code data} 的 key(含嵌套 Map),命中关键字即抛 BizException 拒入。
   *
   * @param data 待扫描的 Map(可为 null,直接返回)
   * @param contextLabel 上下文标识(写入异常 args[0]),例如 {@code "atomic.shell.parameters"} / {@code
   *     "sdk.taskType.descriptor"}
   * @throws BizException 命中关键字时,messageKey={@code error.security.sensitive_in_payload}
   */
  public static void rejectIfContainsSensitiveKeys(Map<String, ?> data, String contextLabel) {
    if (data == null || data.isEmpty()) {
      return;
    }
    String hit = findFirstSensitiveKey(data, Set.of());
    if (hit != null) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.security.sensitive_in_payload", contextLabel, hit);
    }
  }

  /** boolean 版:dry-run / 警示路径使用,不阻断。 */
  public static boolean containsSensitiveKey(Map<String, ?> data) {
    if (data == null || data.isEmpty()) {
      return false;
    }
    return findFirstSensitiveKey(data, Set.of()) != null;
  }

  /** 递归查找首个命中的 key(返回原始大小写以便诊断)。已访问 Map 用 IdentityHashMap 防自指引循环。 */
  private static String findFirstSensitiveKey(Map<?, ?> data, Set<Object> visited) {
    IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    for (Object v : visited) {
      seen.put(v, Boolean.TRUE);
    }
    return scan(data, seen);
  }

  private static String scan(Map<?, ?> data, IdentityHashMap<Object, Boolean> seen) {
    if (data == null || seen.containsKey(data)) {
      return null;
    }
    seen.put(data, Boolean.TRUE);
    for (Map.Entry<?, ?> entry : data.entrySet()) {
      Object rawKey = entry.getKey();
      if (rawKey != null) {
        String key = rawKey.toString();
        // 大小写不敏感 + 分隔符无关:把 `-` / `_` 都 strip 掉再 contains,
        // 这样 `api_key` / `apiKey` / `x-api-key` / `API-KEY` 都能落到同一规范形态。
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        for (String kw : SENSITIVE_KEYWORDS) {
          String kwNormalized = kw.replace("_", "").replace("-", "");
          if (normalized.contains(kwNormalized)) {
            return key;
          }
        }
      }
      Object value = entry.getValue();
      if (value instanceof Map<?, ?> nested) {
        String found = scan(nested, seen);
        if (found != null) {
          return found;
        }
      } else if (value instanceof Iterable<?> it) {
        for (Object item : it) {
          if (item instanceof Map<?, ?> nestedMap) {
            String found = scan(nestedMap, seen);
            if (found != null) {
              return found;
            }
          }
        }
      }
    }
    return null;
  }
}
