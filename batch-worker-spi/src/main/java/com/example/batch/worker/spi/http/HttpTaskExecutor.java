package com.example.batch.worker.spi.http;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskCapability;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * HTTP task SPI 实现 — 用 JDK {@link HttpClient} 发出口 HTTP 请求,带域名白名单 / 超时 / 响应截断 / 简单重试 / 基础鉴权。
 *
 * <p>启用方式:{@code batch.worker.executors.http.enabled=true}(默认 false)。
 *
 * <p>选用 JDK HttpClient 而非 RestClient / OkHttp:避免引第三方依赖 + 原生支持 sync timeout + 同步 API
 * 符合本类执行语义(单步任务,不需要 reactive)。
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

  private final HttpExecutorProperties props;

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
      Invocation inv = parseInvocation(ctx);
      return runWithRetry(ctx, inv);
    } catch (HttpValidationException ex) {
      return TaskResult.fail(ex.getMessage());
    } catch (RuntimeException ex) {
      log.error(
          "http executor unexpected error: tenantId={}, jobCode={}",
          ctx.tenantId(),
          ctx.jobCode(),
          ex);
      return TaskResult.fail(ex);
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
      return; // 空白名单 = 允许全部(仅 dev)
    }
    for (String pattern : props.getAllowedHostPatterns()) {
      if (matchesGlob(pattern.toLowerCase(Locale.ROOT), h)) {
        return;
      }
    }
    throw new HttpValidationException(
        "host not in allowedHostPatterns: " + host + ", allowed=" + props.getAllowedHostPatterns());
  }

  /** 简化 glob:{@code *} = 匹配 0+ 个非 {@code .} 字符;其他字符精确。 */
  static boolean matchesGlob(String pattern, String value) {
    StringBuilder regex = new StringBuilder("^");
    for (char c : pattern.toCharArray()) {
      switch (c) {
        case '*' -> regex.append("[^.]*");
        case '.' -> regex.append("\\.");
        case '?' -> regex.append("[^.]");
        default -> regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
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
      java.util.HashSet<Integer> out = new java.util.HashSet<>();
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
        // no-op
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
        return result.success()
            ? TaskResult.ok(result.message(), outWithAttempts)
            : TaskResult.fail(result.message());
      } catch (java.io.IOException | InterruptedException ex) {
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
    return TaskResult.fail(
        "http failed after "
            + maxAttempts
            + " attempts: "
            + (lastException == null ? "?" : lastException.getMessage()),
        lastException);
  }

  private TaskResult runOnce(Invocation inv, int attempt)
      throws java.io.IOException, InterruptedException {
    HttpClient client = HttpClient.newBuilder().connectTimeout(inv.timeout).build();

    HttpRequest.Builder req = HttpRequest.newBuilder(inv.uri).timeout(inv.timeout);
    inv.headers.forEach(req::header);

    HttpRequest.BodyPublisher bodyPub =
        inv.body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(inv.body);
    switch (inv.method) {
      case "GET" -> req.GET();
      case "DELETE" -> req.DELETE();
      case "POST", "PUT", "PATCH", "HEAD" -> req.method(inv.method, bodyPub);
      default -> req.method(inv.method, bodyPub);
    }

    HttpResponse<byte[]> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());

    byte[] raw = resp.body();
    boolean truncated = false;
    byte[] kept = raw;
    if (raw != null && raw.length > props.getMaxResponseBytes()) {
      kept = new byte[props.getMaxResponseBytes()];
      System.arraycopy(raw, 0, kept, 0, props.getMaxResponseBytes());
      truncated = true;
      log.warn("http response truncated at {} bytes", props.getMaxResponseBytes());
    }
    String responseBody = kept == null ? "" : new String(kept, StandardCharsets.UTF_8);

    Map<String, Object> output = new HashMap<>();
    output.put("statusCode", resp.statusCode());
    output.put("responseHeaders", resp.headers().map());
    output.put("responseBody", responseBody);
    output.put("responseTruncated", truncated);

    boolean expectMatch =
        inv.expectedStatus.isEmpty() || inv.expectedStatus.contains(resp.statusCode());
    if (!expectMatch) {
      return TaskResult.fail(
          "status " + resp.statusCode() + " not in expected " + inv.expectedStatus);
    }
    return TaskResult.ok("status=" + resp.statusCode(), output);
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
