package com.example.batch.sdk.handler.atomic;

import com.example.batch.sdk.handler.SdkAbstractAtomicHandler;
import com.example.batch.sdk.task.SdkTaskContext;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;

/**
 * 开箱即用的 HTTP 原子执行器。继承 {@link SdkAbstractAtomicHandler},发起单次 HTTP 调用并返回状态码 + (截断的) 响应体。
 *
 * <p>用 JDK {@link HttpClient}(无第三方 HTTP 库)。内置 SSRF 防护:发请求**前**解析 host,拦截私网 / 环回 / 链路本地 / 站点本地地址(当
 * {@link HttpAtomicConfig#blockPrivateIps()})与 host 黑名单。method 走白名单。
 *
 * <p><b>参数</b>(取自 {@link SdkTaskContext#parameters()}):
 *
 * <ul>
 *   <li>{@code url}(String,必需)
 *   <li>{@code method}(String,默认 GET)
 *   <li>{@code headers}(Map&lt;String,String&gt;,可空)
 *   <li>{@code body}(String,可空)
 * </ul>
 *
 * <p><b>输出</b>:{@code {"statusCode": int, "responseBody": String, "responseTruncated": bool}}。非 2xx
 * 不抛异常, statusCode 原样返回(success=true,业务自行判断)。
 *
 * <p><b>边界</b>:SSRF 校验在解析阶段做一次(基于 host 的 DNS 解析)。DNS rebinding(解析后到连接之间 host→IP 变化)不在本期范围。
 */
@Slf4j
public class HttpAtomicHandler extends SdkAbstractAtomicHandler<Map<String, Object>> {

  private final HttpAtomicConfig config;
  private final HttpClient httpClient;

  public HttpAtomicHandler(HttpAtomicConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.timeoutSeconds())).build();
  }

  @Override
  public String taskType() {
    return config.taskType();
  }

  @Override
  @SuppressWarnings("unchecked")
  protected Map<String, Object> doInvoke(SdkTaskContext ctx) throws Exception {
    Map<String, Object> params = ctx.parameters();
    String url = asString(params.get("url"));
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("missing required parameter: url");
    }
    String method =
        Objects.requireNonNullElse(asString(params.get("method")), "GET").toUpperCase(Locale.ROOT);
    Map<String, String> headers = (Map<String, String>) params.get("headers");
    String body = asString(params.get("body"));

    // method 闸
    if (!config.allowedMethods().contains(method)) {
      throw new IllegalArgumentException("HTTP method not allowed: " + method);
    }

    URI uri = URI.create(url);
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("invalid url, no host: " + url);
    }

    // SSRF 闸 — 必须在发请求之前
    checkSsrf(host);

    // 构造请求
    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(config.timeoutSeconds()))
            .method(
                method,
                body == null
                    ? BodyPublishers.noBody()
                    : BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (headers != null) {
      headers.forEach(reqBuilder::header);
    }

    HttpResponse<byte[]> resp = httpClient.send(reqBuilder.build(), BodyHandlers.ofByteArray());

    byte[] raw = resp.body() == null ? new byte[0] : resp.body();
    boolean truncated = raw.length > config.maxResponseBytes();
    int len = truncated ? config.maxResponseBytes() : raw.length;
    String responseBody = new String(raw, 0, len, StandardCharsets.UTF_8);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("statusCode", resp.statusCode());
    out.put("responseBody", responseBody);
    out.put("responseTruncated", truncated);
    return out;
  }

  private void checkSsrf(String host) throws Exception {
    if (config.blockPrivateIps()) {
      InetAddress addr = InetAddress.getByName(host);
      if (addr.isLoopbackAddress()
          || addr.isAnyLocalAddress()
          || addr.isLinkLocalAddress()
          || addr.isSiteLocalAddress()) {
        throw new SecurityException("SSRF blocked: private/loopback IP for host " + host);
      }
    }
    for (String pattern : config.blockedHostPatterns()) {
      if (host.contains(pattern) || matchesRegex(host, pattern)) {
        throw new SecurityException("SSRF blocked: host matches blocked pattern " + pattern);
      }
    }
  }

  /** 黑名单可填正则;非法正则视为不匹配(已先做 contains 子串匹配兜底)。 */
  private static boolean matchesRegex(String host, String pattern) {
    try {
      return host.matches(pattern);
    } catch (PatternSyntaxException ex) {
      return false;
    }
  }

  private static String asString(Object v) {
    return v == null ? null : v.toString();
  }

  @Override
  protected Map<String, Object> asOutput(Map<String, Object> r) {
    return r;
  }
}
