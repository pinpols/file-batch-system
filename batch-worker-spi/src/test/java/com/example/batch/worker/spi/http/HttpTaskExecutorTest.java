package com.example.batch.worker.spi.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link HttpTaskExecutor} 单测 — validation / 黑白名单 / 真实 HTTP server(JDK {@link HttpServer})。 */
class HttpTaskExecutorTest {

  private HttpExecutorProperties props;
  private HttpTaskExecutor executor;
  private HttpServer server;
  private int serverPort;

  @BeforeEach
  void setUp() throws Exception {
    props = new HttpExecutorProperties();
    props.setEnabled(true);
    props.setDefaultTimeout(Duration.ofSeconds(3));
    // 测试要打到 localhost:<port>,默认 blockedHostPatterns 含 localhost / 127.* → 覆盖为空
    props.setBlockedHostPatterns(Set.of());
    // 本类测 HTTP 机制(打 127.0.0.1 mock),非 SSRF 策略;关 blockPrivateIps,SSRF 策略另见
    // HttpTaskExecutorIpBlockTest
    props.setBlockPrivateIps(false);
    executor = new HttpTaskExecutor(props);

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverPort = server.getAddress().getPort();
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  private TaskContext ctxWithParams(Map<String, Object> params) {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", params, Map.of());
  }

  private String url(String path) {
    return "http://127.0.0.1:" + serverPort + path;
  }

  // ─── Validation ──────────────────────────────────────────────────────────────

  @Nested
  class Validation {

    @Test
    void rejectsMissingUrl() {
      TaskResult r = executor.execute(ctxWithParams(Map.of()));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("parameters.url required");
    }

    @Test
    void rejectsInvalidUrl() {
      TaskResult r = executor.execute(ctxWithParams(Map.of("url", "not a uri @@@")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not a valid URI");
    }

    @Test
    void rejectsUrlWithoutHost() {
      TaskResult r = executor.execute(ctxWithParams(Map.of("url", "file:///etc/passwd")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("URL must have host");
    }

    @Test
    void rejectsMethodNotInWhitelist() {
      props.setAllowedMethods(Set.of("GET"));
      TaskResult r =
          executor.execute(
              ctxWithParams(Map.of("url", "http://api.example.com", "method", "DELETE")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not in allowedMethods");
    }

    @Test
    void rejectsBadExpectStatusType() {
      TaskResult r =
          executor.execute(
              ctxWithParams(
                  Map.of("url", "http://api.example.com", "expectStatus", "not-a-number")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("expectStatus must be Integer or List");
    }

    @Test
    void rejectsBadAuthType() {
      TaskResult r =
          executor.execute(
              ctxWithParams(
                  Map.of("url", "http://api.example.com", "auth", Map.of("type", "oauth"))));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not in allowedAuthTypes");
    }

    @Test
    void rejectsBearerWithoutToken() {
      TaskResult r =
          executor.execute(
              ctxWithParams(
                  Map.of("url", "http://api.example.com", "auth", Map.of("type", "bearer"))));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("auth.token required");
    }
  }

  // ─── Host black/whitelist ───────────────────────────────────────────────────

  @Nested
  class HostFiltering {

    @Test
    void blocksMetadataServiceByDefault() {
      props.setBlockedHostPatterns(Set.of("169.254.169.254", "metadata.google.internal"));
      TaskResult r =
          executor.execute(
              ctxWithParams(Map.of("url", "http://169.254.169.254/latest/meta-data/")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("host blocked");
    }

    @Test
    void blocksLocalhostByDefault() {
      props.setBlockedHostPatterns(Set.of("localhost", "127.*"));
      TaskResult r = executor.execute(ctxWithParams(Map.of("url", "http://localhost:9999/foo")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("host blocked");
    }

    @Test
    void whitelistRejectsNonMatch() {
      props.setAllowedHostPatterns(Set.of("*.example.com"));
      TaskResult r = executor.execute(ctxWithParams(Map.of("url", "http://api.evil.com/x")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not in allowedHostPatterns");
    }

    @Test
    void whitelistAllowsMatch() {
      // 不发真请求,host 校验过 → 后面真请求会因找不到 host fail,但不是 validation fail
      props.setAllowedHostPatterns(Set.of("*.unreachable.test"));
      // 用很短超时避免长时间 hang
      props.setDefaultTimeout(Duration.ofMillis(100));
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("url", "http://foo.unreachable.test/x")));
      // 校验通过 → 真请求失败 → 错误不含 "not in allowedHostPatterns"
      assertThat(r.message()).doesNotContain("not in allowedHostPatterns");
    }

    @Test
    void blockedTakesPriorityOverAllowed() {
      props.setAllowedHostPatterns(Set.of("*"));
      props.setBlockedHostPatterns(Set.of("evil.com"));
      TaskResult r = executor.execute(ctxWithParams(Map.of("url", "http://evil.com/x")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("host blocked");
    }
  }

  // ─── Glob matching ─────────────────────────────────────────────────────────

  @Nested
  class GlobMatch {

    @Test
    void starMatchesNonDotSegment() {
      assertThat(HttpTaskExecutor.matchesGlob("api.*.com", "api.foo.com")).isTrue();
      assertThat(HttpTaskExecutor.matchesGlob("api.*.com", "api.foo.bar.com")).isFalse();
    }

    @Test
    void plainHostMatches() {
      assertThat(HttpTaskExecutor.matchesGlob("api.example.com", "api.example.com")).isTrue();
      assertThat(HttpTaskExecutor.matchesGlob("api.example.com", "api.example.org")).isFalse();
    }

    @Test
    void starPrefix() {
      assertThat(HttpTaskExecutor.matchesGlob("*.example.com", "foo.example.com")).isTrue();
      assertThat(HttpTaskExecutor.matchesGlob("*.example.com", "example.com")).isFalse();
    }
  }

  // ─── Capability ─────────────────────────────────────────────────────────────

  @Test
  void capabilityReflectsConfig() {
    assertThat(executor.taskType()).isEqualTo("http");
    assertThat(executor.capability().resourceKinds()).containsExactly(ResourceKind.NET);
    assertThat(executor.capability().idempotent()).isFalse();
  }

  // ─── Real HTTP ──────────────────────────────────────────────────────────────

  @Nested
  class RealHttp {

    @Test
    void getSuccess() {
      server.createContext(
          "/hello",
          ex -> {
            byte[] body = "world".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
          });

      TaskResult r = executor.execute(ctxWithParams(Map.of("url", url("/hello"))));

      assertThat(r.success()).isTrue();
      assertThat(r.output()).containsEntry("statusCode", 200);
      assertThat(r.output().get("responseBody")).isEqualTo("world");
    }

    @Test
    void postWithBody() {
      AtomicInteger received = new AtomicInteger();
      server.createContext(
          "/echo",
          ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            received.set(body.length);
            ex.sendResponseHeaders(201, body.length);
            ex.getResponseBody().write(body);
            ex.close();
          });

      TaskResult r =
          executor.execute(
              ctxWithParams(
                  Map.of(
                      "url",
                      url("/echo"),
                      "method",
                      "POST",
                      "body",
                      "{\"foo\":\"bar\"}",
                      "expectStatus",
                      201)));

      assertThat(r.success()).isTrue();
      assertThat(r.output()).containsEntry("statusCode", 201);
      assertThat(received.get()).isEqualTo(13);
    }

    @Test
    void expectStatusMismatchFails() {
      server.createContext(
          "/notfound",
          ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
          });

      TaskResult r =
          executor.execute(ctxWithParams(Map.of("url", url("/notfound"), "expectStatus", 200)));

      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("status 404 not in expected");
    }

    @Test
    void basicAuthHeaderInjected() {
      AtomicInteger gotAuth = new AtomicInteger();
      server.createContext(
          "/auth",
          ex -> {
            String h = ex.getRequestHeaders().getFirst("Authorization");
            if (h != null && h.startsWith("Basic ")) gotAuth.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
          });

      TaskResult r =
          executor.execute(
              ctxWithParams(
                  Map.of(
                      "url",
                      url("/auth"),
                      "auth",
                      Map.of("type", "basic", "username", "u", "password", "p"))));

      assertThat(r.success()).isTrue();
      assertThat(gotAuth.get()).isEqualTo(1);
    }

    @Test
    void bearerAuthHeaderInjected() {
      AtomicInteger gotBearer = new AtomicInteger();
      server.createContext(
          "/bearer",
          ex -> {
            String h = ex.getRequestHeaders().getFirst("Authorization");
            if ("Bearer xyz".equals(h)) gotBearer.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
          });

      executor.execute(
          ctxWithParams(
              Map.of("url", url("/bearer"), "auth", Map.of("type", "bearer", "token", "xyz"))));

      assertThat(gotBearer.get()).isEqualTo(1);
    }

    @Test
    void truncatesLargeResponse() {
      props.setMaxResponseBytes(10);
      server.createContext(
          "/big",
          ex -> {
            byte[] body = new byte[1000];
            java.util.Arrays.fill(body, (byte) 'X');
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
          });

      TaskResult r = executor.execute(ctxWithParams(Map.of("url", url("/big"))));

      assertThat(r.success()).isTrue();
      assertThat(((String) r.output().get("responseBody")).length()).isEqualTo(10);
      assertThat(r.output()).containsEntry("responseTruncated", true);
    }

    @Test
    void retriesIdempotentOn5xx() {
      props.setMaxRetries(2);
      props.setRetryBackoff(Duration.ofMillis(10));
      AtomicInteger calls = new AtomicInteger();
      server.createContext(
          "/flaky",
          ex -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
              ex.sendResponseHeaders(503, -1);
            } else {
              byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
              ex.sendResponseHeaders(200, body.length);
              ex.getResponseBody().write(body);
            }
            ex.close();
          });

      TaskResult r = executor.execute(ctxWithParams(Map.of("url", url("/flaky"))));

      assertThat(r.success()).isTrue();
      assertThat(r.output()).containsEntry("attempts", 3);
      assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void doesNotRetryNonIdempotentOn5xx() {
      props.setMaxRetries(2);
      AtomicInteger calls = new AtomicInteger();
      server.createContext(
          "/post-fail",
          ex -> {
            calls.incrementAndGet();
            ex.sendResponseHeaders(503, -1);
            ex.close();
          });

      executor.execute(ctxWithParams(Map.of("url", url("/post-fail"), "method", "POST")));

      // POST 不重试,只 1 次
      assertThat(calls.get()).isEqualTo(1);
    }
  }
}
