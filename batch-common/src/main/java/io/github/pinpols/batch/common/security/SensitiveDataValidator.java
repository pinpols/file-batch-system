package io.github.pinpols.batch.common.security;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.SecretMasking;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Lane C — 凭据字段静态拒入闸门。
 *
 * <p>用途:Worker 收到 task parameters / SDK 上报 descriptor / orchestrator 接收 heartbeat 时,递归扫描 Map key,
 * 一旦命中高风险关键字(password / secret / apiKey / token / credential / accessKey / privateKey /
 * clientSecret), 即抛 {@link BizException} 拒入。命令行参数或 URI 中有明确凭据语法时，由调用方显式调用本类的内联值校验；
 * 无标记的秘密值无法通过字面扫描可靠识别，写入边界仍须按字段契约约束。
 *
 * <p>命中策略:key 转小写后做"包含"匹配(子串足够 → 例如 {@code db_password} / {@code my-api-key} 都会被拦),大小写不敏感。 同时下钻任意层级的
 * {@link Map} / {@link Iterable}(对 {@code defaults} / {@code inputSchema} 这种嵌套结构有效)。
 *
 * <p><b>已知误报风险</b>:像 {@code password_must_change} / {@code token_expiry} 这种"非凭据本身但名字含关键词"
 * 的字段也会被拦。当前策略偏 fail-closed:宁可让调用方重命名(改 {@code mustChangePassword} 字段名亦无效,内部还是 contains
 * "password"),也不放行真凭据。后续如需放行白名单,在 {@link #SENSITIVE_KEYWORDS} 旁补 allowlist。
 */
public final class SensitiveDataValidator {

  private static final String SENSITIVE_FIELD_MARKER = "sensitiveField";

  /** 命中即拒的关键字(全部小写,匹配规则:key.toLowerCase().contains(keyword))。 */
  static final List<String> SENSITIVE_KEYWORDS = List.of(
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
   * @throws BizException 命中关键字时,messageKey={@code error.security.sensitive_in_payload}；异常参数只含固定标记，
   *     不回显调用方提供的字段名
   */
  public static void rejectIfContainsSensitiveKeys(Map<String, ?> data, String contextLabel) {
    if (data == null || data.isEmpty()) {
      return;
    }
    if (EmptyChecks.isNotNull(findFirstSensitiveKey(data))) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.security.sensitive_in_payload",
          contextLabel,
          SENSITIVE_FIELD_MARKER);
    }
  }

  /** boolean 版:dry-run / 警示路径使用,不阻断。 */
  public static boolean containsSensitiveKey(Map<String, ?> data) {
    if (data == null || data.isEmpty()) {
      return false;
    }
    return EmptyChecks.isNotNull(findFirstSensitiveKey(data));
  }

  /** 拒绝命令行参数或 URI 字符串中具有明确凭据语法的值；不在异常中回显原文。 */
  public static void rejectIfContainsInlineCredential(String value, String contextLabel) {
    if (EmptyChecks.isBlank(value)) {
      return;
    }
    String option = value.stripLeading();
    if (option.startsWith("-")) {
      int start = option.startsWith("--") ? 2 : 1;
      int end = start;
      while (end < option.length()
          && option.charAt(end) != '='
          && option.charAt(end) != ':'
          && !Character.isWhitespace(option.charAt(end))) {
        end++;
      }
      if (isSensitiveKey(option.substring(start, end))) {
        rejectInlineCredential(contextLabel);
      }
    }
    if (SwallowedExceptionLogger.containsCredentialSyntax(value)
        || !Objects.equals(SecretMasking.maskEndpoint(value), value)) {
      rejectInlineCredential(contextLabel);
    }
  }

  /** 只检查传入的参数列表；其它普通业务文本不做全量递归猜测。 */
  public static void rejectIfContainsInlineCredentials(Iterable<?> values, String contextLabel) {
    if (EmptyChecks.isNull(values)) {
      return;
    }
    for (Object value : values) {
      if (value instanceof String text) {
        rejectIfContainsInlineCredential(text, contextLabel);
      }
    }
  }

  private static void rejectInlineCredential(String contextLabel) {
    throw BizException.of(
        ResultCode.INVALID_ARGUMENT,
        "error.security.sensitive_in_payload",
        contextLabel,
        "inlineCredential");
  }

  private static boolean isSensitiveKey(String key) {
    String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    for (String keyword : SENSITIVE_KEYWORDS) {
      if (normalized.contains(keyword.replace("_", "").replace("-", ""))) {
        return true;
      }
    }
    return false;
  }

  /** 递归查找首个命中的 key；已访问 Map/Iterable 用身份标识防自引用循环。原始 key 只在内部用于判断，不进入异常参数。 */
  private static String findFirstSensitiveKey(Map<?, ?> data) {
    return scan(data, new IdentityHashMap<>());
  }

  private static String scan(Object data, IdentityHashMap<Object, Boolean> seen) {
    if (!(data instanceof Map<?, ?>) && !(data instanceof Iterable<?>)) {
      return null;
    }
    if (EmptyChecks.isNotNull(seen.put(data, Boolean.TRUE))) {
      return null;
    }
    if (data instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        Object rawKey = entry.getKey();
        if (EmptyChecks.isNotNull(rawKey)) {
          String key = rawKey.toString();
          // 大小写不敏感 + 分隔符无关:把 `-` / `_` 都 strip 掉再 contains。
          if (isSensitiveKey(key)) {
            return key;
          }
        }
        String found = scan(entry.getValue(), seen);
        if (EmptyChecks.isNotNull(found)) {
          return found;
        }
      }
    } else if (data instanceof Iterable<?> values) {
      for (Object value : values) {
        String found = scan(value, seen);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }
}
