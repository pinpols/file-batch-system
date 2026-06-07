package com.example.batch.sdk.handler.atomic;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link HttpAtomicHandler} 用 JDK {@link HttpServer} 起 stub server 跑真 HTTP,经基类 execute 验证终态。 */
class HttpAtomicHandlerTest {

  private HttpServer server;
  private int port;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.stop(0);
  }

  /** stub 在 127.0.0.1(环回),要打真请求必须关 blockPrivateIps。 */
  private HttpAtomicConfig allowLoopback() {
    return new HttpAtomicConfig("http", false, Set.of(), Set.of(), 5, 1024 * 1024);
  }

  private SdkTaskContext ctx(Map<String, Object> params) {
    return new SdkTaskContext("tx", "j", "ti", 1L, "w-1", params, Map.of());
  }

  @Test
  @DisplayName("GET 200 返回 statusCode + responseBody")
  void shouldReturn200Body_whenGet() {
    // 准备
    server.createContext(
        "/x",
        ex -> {
          byte[] b = "hello".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    HttpAtomicHandler h = new HttpAtomicHandler(allowLoopback());

    // 执行
    SdkTaskResult r = h.execute(ctx(Map.of("url", "http://127.0.0.1:" + port + "/x")));

    // 断言
    assertThat(r.success()).isTrue();
    assertThat(r.output()).containsEntry("statusCode", 200);
    assertThat(r.output()).containsEntry("responseBody", "hello");
    assertThat(r.output()).containsEntry("responseTruncated", false);
  }

  @Test
  @DisplayName("POST 带 body,server 收到 body")
  void shouldSendBody_whenPost() {
    // 准备
    AtomicReference<String> seen = new AtomicReference<>();
    server.createContext(
        "/p",
        ex -> {
          seen.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    HttpAtomicHandler h = new HttpAtomicHandler(allowLoopback());
    Map<String, Object> params =
        Map.of(
            "url", "http://127.0.0.1:" + port + "/p",
            "method", "post",
            "body", "payload-123");

    // 执行
    SdkTaskResult r = h.execute(ctx(params));

    // 断言
    assertThat(r.success()).isTrue();
    assertThat(seen.get()).isEqualTo("payload-123");
  }

  @Test
  @DisplayName("缺 url → fail")
  void shouldFail_whenUrlMissing() {
    HttpAtomicHandler h = new HttpAtomicHandler(allowLoopback());

    SdkTaskResult r = h.execute(ctx(Map.of("method", "GET")));

    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("url");
  }

  @Test
  @DisplayName("method 不在白名单 → fail")
  void shouldFail_whenMethodNotAllowed() {
    HttpAtomicConfig cfg = new HttpAtomicConfig("http", false, Set.of(), Set.of("GET"), 5, 1024);
    HttpAtomicHandler h = new HttpAtomicHandler(cfg);
    Map<String, Object> params =
        Map.of("url", "http://127.0.0.1:" + port + "/x", "method", "DELETE");

    SdkTaskResult r = h.execute(ctx(params));

    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("DELETE");
  }

  @Test
  @DisplayName("SSRF:blockPrivateIps + 环回地址 → fail(不真发)")
  void shouldFail_whenSsrfLoopbackBlocked() {
    HttpAtomicHandler h = new HttpAtomicHandler(HttpAtomicConfig.defaults("http"));

    SdkTaskResult r = h.execute(ctx(Map.of("url", "http://127.0.0.1/x")));

    assertThat(r.success()).isFalse();
    assertThat(r.error()).isInstanceOf(SecurityException.class);
    assertThat(r.message()).contains("SSRF");
  }

  @Test
  @DisplayName("SSRF:host 命中黑名单 → fail")
  void shouldFail_whenHostBlacklisted() {
    HttpAtomicConfig cfg =
        new HttpAtomicConfig("http", false, Set.of("evil.example"), Set.of(), 5, 1024);
    HttpAtomicHandler h = new HttpAtomicHandler(cfg);

    SdkTaskResult r = h.execute(ctx(Map.of("url", "http://evil.example/x")));

    assertThat(r.success()).isFalse();
    assertThat(r.error()).isInstanceOf(SecurityException.class);
    assertThat(r.message()).contains("blocked pattern");
  }

  @Test
  @DisplayName("响应超 maxResponseBytes → responseTruncated=true")
  void shouldTruncate_whenResponseTooLarge() {
    // 准备
    server.createContext(
        "/big",
        ex -> {
          byte[] b = "abcdefghij".getBytes(StandardCharsets.UTF_8); // 10 bytes
          ex.sendResponseHeaders(200, b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    HttpAtomicConfig cfg = new HttpAtomicConfig("http", false, Set.of(), Set.of(), 5, 4);
    HttpAtomicHandler h = new HttpAtomicHandler(cfg);

    // 执行
    SdkTaskResult r = h.execute(ctx(Map.of("url", "http://127.0.0.1:" + port + "/big")));

    // 断言
    assertThat(r.success()).isTrue();
    assertThat(r.output()).containsEntry("responseTruncated", true);
    assertThat(r.output()).containsEntry("responseBody", "abcd");
  }

  @Test
  @DisplayName("非 2xx(500)仍返回 statusCode,success=true")
  void shouldReturn500_whenServerError() {
    // 准备
    server.createContext(
        "/err",
        ex -> {
          byte[] b = "boom".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(500, b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    HttpAtomicHandler h = new HttpAtomicHandler(allowLoopback());

    // 执行
    SdkTaskResult r = h.execute(ctx(Map.of("url", "http://127.0.0.1:" + port + "/err")));

    // 断言
    assertThat(r.success()).isTrue();
    assertThat(r.output()).containsEntry("statusCode", 500);
    assertThat(r.output()).containsEntry("responseBody", "boom");
  }

  @Test
  @DisplayName("custom headers 透传到 server")
  void shouldForwardHeaders_whenProvided() {
    // 准备
    AtomicReference<String> seen = new AtomicReference<>();
    server.createContext(
        "/h",
        ex -> {
          seen.set(ex.getRequestHeaders().getFirst("X-Custom"));
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    HttpAtomicHandler h = new HttpAtomicHandler(allowLoopback());
    Map<String, Object> headers = new HashMap<>();
    headers.put("X-Custom", "v1");
    Map<String, Object> params = new HashMap<>();
    params.put("url", "http://127.0.0.1:" + port + "/h");
    params.put("headers", headers);

    // 执行
    SdkTaskResult r = h.execute(ctx(params));

    // 断言
    assertThat(r.success()).isTrue();
    assertThat(seen.get()).isEqualTo("v1");
  }

  @Test
  @DisplayName("taskType() 来自 config")
  void shouldExposeTaskType_fromConfig() {
    HttpAtomicHandler h = new HttpAtomicHandler(HttpAtomicConfig.defaults("my-http"));
    assertThat(h.taskType()).isEqualTo("my-http");
  }
}
