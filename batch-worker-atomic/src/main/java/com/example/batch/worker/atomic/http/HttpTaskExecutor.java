package com.example.batch.worker.atomic.http;

import com.example.batch.common.exception.BizException;
import com.example.batch.common.security.SensitiveDataValidator;
import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskCapability;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.worker.atomic.runtime.AtomicErrorCode;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Dns;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * HTTP task SPI 实现 — 用 OkHttp 发出口 HTTP 请求,带域名白名单 / 超时 / 响应截断 / 简单重试 / 基础鉴权。
 *
 * <p>启用方式:{@code batch.worker.executors.http.enabled=true}(默认 false)。
 *
 * <p>选用 OkHttp 而非 JDK HttpClient:OkHttp 的 {@code dns} 回调支持 <b>resolve-then-connect</b>——在真正建连前
 * 把已校验的 IP 交给连接层,杜绝 JDK HttpClient "校验时解析一次、连接时再独立解析一次"的 DNS-rebinding TOCTOU (攻击者权威 DNS 第二次返回内网/云
 * metadata 即可绕过 {@link #validateResolvedIp})。与 worker-dispatch 出口同款。
 *
 * <p>parameters 协议:
 *
 * <ul>
 *   <li>{@code url} (required, String):目标 URL,host 必须不在黑名单 + 在白名单(若配置)
 *   <li>{@code method} (optional, String, default GET):HTTP 方法
 *   <li>{@code headers} (optional, Map&lt;String,String&gt;):自定义 header
 *   <li>{@code body} (optional, String):请求体
 *   <li>{@code timeoutSeconds} (optional, Long):覆盖默认超时,只能缩短
 *   <li>{@code expectStatus} (optional, Integer / List&lt;Integer&gt;):期望的 status code,不匹配 → fail
 *   <li>{@code auth} (optional, Map):鉴权,{@code {"type": "basic", "username": ".", "password": "."}}
 *       或 {@code {"type": "bearer", "token": "."}}
 * </ul>
 *
 * <p>output 协议:
 *
 * <ul>
 *   <li>{@code statusCode} (Integer)
 *   <li>{@code responseHeaders} (Map&lt;String, List&lt;String&gt;&gt;)
 *   <li>{@code responseBody} (String,截断到 maxResponseBytes)
 *   <li>{@code responseTruncated} (Boolean)
 *   <li>{@code durationMillis} (Long)
 *   <li>{@code attempts} (Integer):实际尝试次数(含首次)
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "batch.worker.executors.http",
    name = "enabled",
    havingValue = "true")
@RequiredArgsConstructor
public class HttpTaskExecutor implements BatchTaskExecutor {

  static final String PARAM_URL = "url";
  static final String PARAM_METHOD = "method";
  static final String PARAM_HEADERS = "headers";
  static final String PARAM_BODY = "body";
  static final String PARAM_TIMEOUT = "timeoutSeconds";
  static final String PARAM_EXPECT_STATUS = "expectStatus";
  static final String PARAM_AUTH = "auth";

  private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "PUT", "DELETE");

  /** OkHttp 方法集中要求带 body 的方法(POST/PUT/PATCH/DELETE 可带);GET/HEAD 必须无 body。 */
  private static final Set<String> METHODS_REQUIRE_BODY = Set.of("POST", "PUT", "PATCH");

  private final HttpExecutorProperties props;

  /**
   * 共享 OkHttp 基础客户端(懒构造,因 dns 回调依赖注入的 props)。每次请求经 {@code newBuilder()} 派生 per-invocation
   * 超时,复用连接池/dispatcher。dns 回调做 resolve-then-connect SSRF 校验。
   */
  private volatile OkHttpClient sharedClient;

  private OkHttpClient client() {
    OkHttpClient c = sharedClient;
    if (c == null) {
      synchronized (this) {
        c = sharedClient;
        if (c == null) {
          c =
              new OkHttpClient.Builder()
                  .followRedirects(false) // 重定向 target 不会再过 validateHost/dns 校验,显式禁
                  .followSslRedirects(false)
                  .retryOnConnectionFailure(false) // 重试由本类 runWithRetry 控制,避免双重重试
                  .dns(guardedDns())
                  .build();
          sharedClient = c;
        }
      }
    }
    return c;
  }

  /**
   * resolve-then-connect SSRF 防护:在 OkHttp 真正建连前回调,解析主机名并对解析出的每个 IP 复用本类 {@link #isBlockedAddress}
   * 黑名单校验(回环/私网/link-local/metadata),命中即抛 {@link UnknownHostException} 阻止建连。把"已校验的同一组
   * IP"交给连接层,杜绝二次解析的 rebinding 窗口。 {@code blockPrivateIps=false}(dev/联调)时直接系统解析放行,与 {@link
   * #validateResolvedIp} 同语义。
   */
  private Dns guardedDns() {
    return hostname -> {
      List<InetAddress> resolved = List.of(InetAddress.getAllByName(hostname));
      if (!props.isBlockPrivateIps()) {
        return resolved;
      }
      for (InetAddress addr : resolved) {
        if (isBlockedAddress(addr)) {
          throw new UnknownHostException(
              "host resolves to blocked address: " + hostname + " -> " + addr.getHostAddress());
        }
      }
      return resolved;
    };
  }

  @Override
  public String taskType() {
    return props.getTaskType();
  }

  @Override
  public TaskCapability capability() {
    return new TaskCapability(
        Set.of(ResourceKind.NET),
        false, // 业务可能非幂等(POST 创建资源等),保守
        true,
        props.getDefaultTimeout());
  }

  @Override
  public TaskResult execute(TaskContext ctx) {
    try {
      // Lane C:扫 parameters,但排除 HTTP executor 协议显式的 `auth` 子树(其内
      // username/password/token 是 protocol 一部分)。其他位置出现的 password / token / secret 仍拒。
      // 后续 Lane C-FE follow-up 应在 console 表单层警示用户「auth.password 也强烈建议改用 env reference」。
      Map<String, Object> paramsForScan = ctx.parameters();
      if (paramsForScan != null && paramsForScan.containsKey(PARAM_AUTH)) {
        Map<String, Object> filtered = new LinkedHashMap<>(paramsForScan);
        filtered.remove(PARAM_AUTH);
        SensitiveDataValidator.rejectIfContainsSensitiveKeys(filtered, "atomic.http.parameters");
      } else {
        SensitiveDataValidator.rejectIfContainsSensitiveKeys(
            paramsForScan, "atomic.http.parameters");
      }
      Invocation inv = parseInvocation(ctx);
      if (ctx.isDryRun()) {
        // ADR-026 §dry-run:不发出 HTTP 请求,只回传将要发的 method + url + header keys + body 长度。
        // 不回传 header value(可能含 auth bearer / cookie),不回传 body 文本(可能含敏感载荷)。
        Map<String, Object> planned = new LinkedHashMap<>();
        planned.put("dryRun", true);
        planned.put("plannedAction", "http");
        planned.put("method", inv.method);
        planned.put("url", inv.uri.toString());
        planned.put("headerKeys", List.copyOf(inv.headers.keySet()));
        planned.put(
            "bodyBytes", inv.body == null ? 0 : inv.body.getBytes(StandardCharsets.UTF_8).length);
        planned.put("timeoutSeconds", inv.timeout.toSeconds());
        planned.put("expectStatus", inv.expectedStatus);
        log.info(
            "http executor dry-run skipped real request: tenantId={}, jobCode={}, method={},"
                + " url={}",
            ctx.tenantId(),
            ctx.jobCode(),
            inv.method,
            inv.uri);
        return TaskResult.ok("dry-run: " + inv.method + " " + inv.uri + " (not sent)", planned);
      }
      return runWithRetry(ctx, inv);
    } catch (HttpValidationException ex) {
      // 域名/IP/SSRF/方法白名单 vs 入参格式问题:都分流到 SECURITY_REJECTED / CONFIG_INVALID
      boolean security =
          ex.getMessage() != null
              && (ex.getMessage().contains("blocked")
                  || ex.getMessage().contains("allowedHostPatterns")
                  || ex.getMessage().contains("allowlist")
                  || ex.getMessage().contains("resolves to blocked"));
      return AtomicErrorCode.fail(
          security ? AtomicErrorCode.SECURITY_REJECTED : AtomicErrorCode.CONFIG_INVALID,
          ex.getMessage());
    } catch (BizException ex) {
      log.warn(
          "http executor rejected by SensitiveDataValidator: tenantId={}, jobCode={}, key={}",
          ctx.tenantId(),
          ctx.jobCode(),
          ex.getMessageArgs() == null || ex.getMessageArgs().length < 2
              ? "?"
              : ex.getMessageArgs()[1]);
      return AtomicErrorCode.fail(
          AtomicErrorCode.SECURITY_REJECTED, "SENSITIVE_DATA_IN_PARAMETERS: " + ex.getMessage());
    } catch (RuntimeException ex) {
      log.error(
          "http executor unexpected error: tenantId={}, jobCode={}",
          ctx.tenantId(),
          ctx.jobCode(),
          ex);
      return AtomicErrorCode.fail(
          AtomicErrorCode.EXECUTION_FAILED,
          ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
          ex);
    }
  }

  // ─── parsing + validation ────────────────────────────────────────────────────

  private Invocation parseInvocation(TaskContext ctx) {
    Map<String, Object> params = ctx.parameters();

    Object urlObj = params.get(PARAM_URL);
    if (!(urlObj instanceof String) || ((String) urlObj).isBlank()) {
      throw new HttpValidationException("parameters.url required");
    }
    URI uri;
    try {
      uri = new URI(((String) urlObj).trim());
    } catch (URISyntaxException e) {
      throw new HttpValidationException("parameters.url not a valid URI: " + e.getMessage());
    }
    if (uri.getHost() == null) {
      throw new HttpValidationException("URL must have host: " + uri);
    }
    validateHost(uri.getHost());

    String method = stringParam(params, PARAM_METHOD, "GET").toUpperCase(Locale.ROOT);
    if (!props.getAllowedMethods().contains(method)) {
      throw new HttpValidationException(
          "method " + method + " not in allowedMethods=" + props.getAllowedMethods());
    }

    @SuppressWarnings("unchecked")
    Map<String, String> headers =
        params.get(PARAM_HEADERS) instanceof Map<?, ?> m
            ? toStringMap((Map<?, ?>) m, "headers")
            : Map.of();

    String body = params.get(PARAM_BODY) instanceof String s ? s : null;

    Duration timeout = props.getDefaultTimeout();
    Object t = params.get(PARAM_TIMEOUT);
    if (t instanceof Number) {
      long sec = ((Number) t).longValue();
      if (sec <= 0) {
        throw new HttpValidationException("timeoutSeconds must be positive");
      }
      Duration requested = Duration.ofSeconds(sec);
      if (requested.compareTo(props.getDefaultTimeout()) < 0) {
        timeout = requested;
      }
    }

    Set<Integer> expectedStatus = parseExpectStatus(params.get(PARAM_EXPECT_STATUS));

    Map<String, String> finalHeaders = new LinkedHashMap<>(headers);
    applyAuth(finalHeaders, params.get(PARAM_AUTH));

    return new Invocation(uri, method, finalHeaders, body, timeout, expectedStatus);
  }

  private void validateHost(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    for (String pattern : props.getBlockedHostPatterns()) {
      if (matchesGlob(pattern.toLowerCase(Locale.ROOT), h)) {
        throw new HttpValidationException("host blocked: " + host + " (matched " + pattern + ")");
      }
    }
    if (props.getAllowedHostPatterns().isEmpty()) {
      // 空白名单:enforceAllowlist=false → 允许全部(仅 dev);=true → fail-closed 拒绝全部
      if (props.isEnforceAllowlist()) {
        throw new HttpValidationException(
            "allowlist enforced but allowedHostPatterns empty (deny all): " + host);
      }
      validateResolvedIp(host);
      return;
    }
    for (String pattern : props.getAllowedHostPatterns()) {
      if (matchesGlob(pattern.toLowerCase(Locale.ROOT), h)) {
        validateResolvedIp(host);
        return;
      }
    }
    throw new HttpValidationException(
        "host not in allowedHostPatterns: " + host + ", allowed=" + props.getAllowedHostPatterns());
  }

  /**
   * SSRF 加固:把 host 解析为 IP,任意一个落入内网 / 回环 / link-local / metadata 网段就拒绝。 字面 IP / 主机名都解析(getAllByName
   * 对 IP literal 不走网络)。由 {@link HttpExecutorProperties#isBlockPrivateIps()} 控制(默认 true)。
   */
  private void validateResolvedIp(String host) {
    if (!props.isBlockPrivateIps()) {
      return;
    }
    InetAddress[] resolved;
    try {
      resolved = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      throw new HttpValidationException("host不能解析: " + host + " (" + e.getMessage() + ")");
    }
    for (InetAddress addr : resolved) {
      if (isBlockedAddress(addr)) {
        throw new HttpValidationException(
            "host resolves to blocked address: " + host + " -> " + addr.getHostAddress());
      }
    }
  }

  /**
   * 判定一个 {@link InetAddress} 是否属于必须拒绝的网段:回环 / link-local / site-local / any-local / 组播 /
   * 169.254.0.0/16,以及其 IPv4-mapped-IPv6 形态({@code ::ffff:a.b.c.d})。 package-private 以便单测直接对 IP
   * literal 校验,无需联网。
   */
  static boolean isBlockedAddress(InetAddress addr) {
    if (addr.isLoopbackAddress()
        || addr.isLinkLocalAddress()
        || addr.isSiteLocalAddress()
        || addr.isAnyLocalAddress()
        || addr.isMulticastAddress()) {
      return true;
    }
    // 169.254.0.0/16 (含 IPv4-mapped IPv6 的 ::ffff:169.254.x.x:取地址末 4 字节判定 v4 段)
    byte[] raw = addr.getAddress();
    byte[] v4 = null;
    if (raw.length == 4) {
      v4 = raw;
    } else if (raw.length == 16 && (addr instanceof Inet6Address i6) && isIpv4Mapped(i6)) {
      v4 = new byte[] {raw[12], raw[13], raw[14], raw[15]};
    }
    if (v4 != null) {
      int b0 = v4[0] & 0xFF;
      int b1 = v4[1] & 0xFF;
      // 169.254.0.0/16 (link-local,某些映射形态 isLinkLocalAddress 可能漏判)
      if (b0 == 169 && b1 == 254) {
        return true;
      }
    }
    return false;
  }

  /** {@code ::ffff:a.b.c.d} 形态:前 10 字节 0,第 11/12 字节为 0xFF。 */
  private static boolean isIpv4Mapped(Inet6Address addr) {
    byte[] b = addr.getAddress();
    for (int i = 0; i < 10; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    return (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
  }

  /** 简化 glob:{@code *} = 匹配 0+ 个非 {@code .} 字符;其他字符精确。 */
  static boolean matchesGlob(String pattern, String value) {
    StringBuilder regex = new StringBuilder("^");
    for (char c : pattern.toCharArray()) {
      switch (c) {
        case '*' -> regex.append("[^.]*");
        case '.' -> regex.append("\\.");
        case '?' -> regex.append("[^.]");
        default -> regex.append(Pattern.quote(String.valueOf(c)));
      }
    }
    regex.append("$");
    return value.matches(regex.toString());
  }

  @SuppressWarnings("unchecked")
  private Set<Integer> parseExpectStatus(Object raw) {
    if (raw == null) {
      return Set.of();
    }
    if (raw instanceof Number n) {
      return Set.of(n.intValue());
    }
    if (raw instanceof List<?> list) {
      HashSet<Integer> out = new HashSet<>();
      for (Object o : list) {
        if (o instanceof Number) {
          out.add(((Number) o).intValue());
        } else {
          throw new HttpValidationException("expectStatus list must contain integers");
        }
      }
      return out;
    }
    throw new HttpValidationException("expectStatus must be Integer or List<Integer>");
  }

  private void applyAuth(Map<String, String> headers, Object rawAuth) {
    if (rawAuth == null) {
      return;
    }
    if (!(rawAuth instanceof Map<?, ?>)) {
      throw new HttpValidationException("auth must be a map");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> auth = (Map<String, Object>) rawAuth;
    String type = String.valueOf(auth.getOrDefault("type", "none")).toLowerCase(Locale.ROOT);
    if (!props.getAllowedAuthTypes().contains(type)) {
      throw new HttpValidationException(
          "auth type " + type + " not in allowedAuthTypes=" + props.getAllowedAuthTypes());
    }
    switch (type) {
      case "none" -> {
        // 无操作
      }
      case "basic" -> {
        String user = Objects.toString(auth.get("username"), "");
        String pass = Objects.toString(auth.get("password"), "");
        String token =
            Base64.getEncoder()
                .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        headers.put("Authorization", "Basic " + token);
      }
      case "bearer" -> {
        String token = Objects.toString(auth.get("token"), "");
        if (token.isEmpty()) {
          throw new HttpValidationException("auth.token required for bearer");
        }
        headers.put("Authorization", "Bearer " + token);
      }
      default -> throw new HttpValidationException("unsupported auth.type: " + type);
    }
  }

  private static Map<String, String> toStringMap(Map<?, ?> m, String fieldName) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : m.entrySet()) {
      if (e.getValue() == null) continue;
      out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
    }
    return out;
  }

  private static String stringParam(Map<String, Object> p, String key, String fallback) {
    Object v = p.get(key);
    return v instanceof String && !((String) v).isBlank() ? ((String) v).trim() : fallback;
  }

  // ─── execution with retry ──────────────────────────────────────────────────

  private TaskResult runWithRetry(TaskContext ctx, Invocation inv) {
    boolean idempotent = IDEMPOTENT_METHODS.contains(inv.method);
    int maxAttempts = idempotent ? props.getMaxRetries() + 1 : 1;

    long start = System.currentTimeMillis();
    Exception lastException = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        TaskResult result = runOnce(inv, attempt);
        // 5xx + 还有重试机会 → 走重试逻辑
        Integer status = (Integer) result.output().get("statusCode");
        if (status != null && status >= 500 && attempt < maxAttempts) {
          log.warn("http 5xx, retrying: status={}, attempt={}/{}", status, attempt, maxAttempts);
          sleep(props.getRetryBackoff().toMillis() * (1L << (attempt - 1)));
          continue;
        }
        // 注入 attempt 数到 output(record 不可变,只能新建 Map)
        Map<String, Object> outWithAttempts = new HashMap<>(result.output());
        outWithAttempts.put("attempts", attempt);
        outWithAttempts.put("durationMillis", System.currentTimeMillis() - start);
        if (result.success()) {
          return TaskResult.ok(result.message(), outWithAttempts);
        }
        // 失败路径:只透传 error_code,不带成功 output(保持与原 TaskResult.fail 语义对齐)
        String existingCode = (String) result.output().get(AtomicErrorCode.OUTPUT_KEY);
        AtomicErrorCode code =
            existingCode == null
                ? AtomicErrorCode.EXECUTION_FAILED
                : AtomicErrorCode.valueOf(existingCode);
        return AtomicErrorCode.fail(code, result.message());
      } catch (IOException ex) {
        // OkHttp 同步 execute 把超时/连接失败/被 dns 守护拦截(UnknownHostException)统一抛 IOException。
        lastException = ex;
        if (Thread.currentThread().isInterrupted()) {
          Thread.currentThread().interrupt();
          break;
        }
        if (attempt < maxAttempts) {
          log.warn(
              "http I/O error, retrying: attempt={}/{}, ex={}",
              attempt,
              maxAttempts,
              ex.getMessage());
          sleep(props.getRetryBackoff().toMillis() * (1L << (attempt - 1)));
        }
      }
    }
    // I/O 异常重试耗尽 = TIMEOUT / 网络层失败,归到 EXECUTION_FAILED(底层无法静态分辨 connect timeout vs read fail)
    return AtomicErrorCode.fail(
        AtomicErrorCode.EXECUTION_FAILED,
        "http failed after "
            + maxAttempts
            + " attempts: "
            + (lastException == null ? "?" : lastException.getMessage()),
        lastException);
  }

  private TaskResult runOnce(Invocation inv, int attempt) throws IOException {
    // 共享客户端的 dns 回调做 resolve-then-connect SSRF 校验(防 rebinding);followRedirects=false
    // 已在 client() 构造期设定(重定向 target 不会再过 validateHost/dns 校验)。
    OkHttpClient call =
        client()
            .newBuilder()
            .connectTimeout(inv.timeout)
            .readTimeout(inv.timeout)
            .writeTimeout(inv.timeout)
            .callTimeout(inv.timeout)
            .build();

    Request.Builder req = new Request.Builder().url(inv.uri.toString());
    Headers.Builder headers = new Headers.Builder();
    inv.headers.forEach(headers::add);
    req.headers(headers.build());

    RequestBody body = toRequestBody(inv);
    req.method(inv.method, body);

    Map<String, Object> output = new HashMap<>();
    int statusCode;
    try (Response resp = call.newCall(req.build()).execute()) {
      statusCode = resp.code();
      byte[] raw = resp.body() == null ? new byte[0] : resp.body().bytes();
      int max = props.getMaxResponseBytes();
      boolean truncated = raw.length > max;
      byte[] kept = raw;
      if (truncated) {
        kept = new byte[max];
        System.arraycopy(raw, 0, kept, 0, max);
        log.warn("http response truncated at {} bytes", max);
      }
      String responseBody = new String(kept, StandardCharsets.UTF_8);

      output.put("statusCode", statusCode);
      // P2-3(2026-06-03,docs/analysis/2026-06-03-deep-scan-be-security.md):
      // 出口 HTTP response 会被 worker 上报到 task_result.output JSONB(后续可被
      // console / forensic export 读到),Set-Cookie / Authorization 这类回声头若透传落
      // 库就形成"出口请求 session 泄露"。SensitiveDataValidator 只扫入参,响应需在此前置脱敏。
      output.put("responseHeaders", sanitizeResponseHeaders(resp.headers().toMultimap()));
      output.put("responseBody", responseBody);
      output.put("responseTruncated", truncated);
    }

    boolean expectMatch = inv.expectedStatus.isEmpty() || inv.expectedStatus.contains(statusCode);
    if (!expectMatch) {
      return AtomicErrorCode.fail(
          AtomicErrorCode.EXECUTION_FAILED,
          "status " + statusCode + " not in expected " + inv.expectedStatus);
    }
    return TaskResult.ok("status=" + statusCode, output);
  }

  /** GET/HEAD 必须无 body;其余方法 body 缺省给空体(OkHttp 要求 POST/PUT/PATCH 非 null)。 */
  private RequestBody toRequestBody(Invocation inv) {
    if ("GET".equals(inv.method) || "HEAD".equals(inv.method)) {
      return null;
    }
    String content = inv.body == null ? "" : inv.body;
    if (inv.body == null && !METHODS_REQUIRE_BODY.contains(inv.method)) {
      return null; // DELETE 等可无 body
    }
    MediaType type = mediaTypeOf(inv.headers);
    return RequestBody.create(content.getBytes(StandardCharsets.UTF_8), type);
  }

  /** 从请求头取 Content-Type 作为 body MediaType;缺省 null(OkHttp 不强制)。 */
  private static MediaType mediaTypeOf(Map<String, String> headers) {
    for (Map.Entry<String, String> e : headers.entrySet()) {
      if ("content-type".equalsIgnoreCase(e.getKey()) && e.getValue() != null) {
        return MediaType.parse(e.getValue());
      }
    }
    return null;
  }

  /**
   * 出口 HTTP 响应头落库前的固定脱敏黑名单(case-insensitive,RFC 7230 头名不区分大小写)。
   *
   * <p>对应 docs/analysis/2026-06-03-deep-scan-be-security.md P2-3。这些头若回声到 task_result.output 即等于把
   * 下游会话凭据落 DB / Kafka,后续 forensic export 一并外泄。
   */
  private static final Set<String> SENSITIVE_RESPONSE_HEADERS =
      Set.of("set-cookie", "set-cookie2", "authorization", "proxy-authorization", "cookie");

  static Map<String, List<String>> sanitizeResponseHeaders(Map<String, List<String>> headers) {
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> filtered = new LinkedHashMap<>(headers.size());
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      String name = entry.getKey();
      if (name == null) {
        continue;
      }
      if (SENSITIVE_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
        filtered.put(name, List.of("[REDACTED]"));
        continue;
      }
      filtered.put(name, entry.getValue());
    }
    return filtered;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ─── helper records / exceptions ────────────────────────────────────────────

  private record Invocation(
      URI uri,
      String method,
      Map<String, String> headers,
      String body,
      Duration timeout,
      Set<Integer> expectedStatus) {}

  static final class HttpValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    HttpValidationException(String message) {
      super(message);
    }
  }

  @Configuration
  @EnableConfigurationProperties(HttpExecutorProperties.class)
  static class PropertiesConfig {}
}
