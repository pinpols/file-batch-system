package io.github.pinpols.batch.worker.atomic.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.spi.task.ResourceKind;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link HttpTaskExecutor} 单测 — validation / 黑白名单 / 真实 HTTP server(JDK {@link HttpServer})。 */
@DisplayName("HTTP 任务执行器: 入参校验, 主机黑白名单与真实请求行为")
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
  @DisplayName("入参校验: 缺失, 非法与越界配置一律快速失败")
  class Validation {

    @Test
    @DisplayName("缺少目标地址时执行应失败, 并提示参数必填")
    void shouldReject_whenUrlMissing() {
      TaskResult r = executor.execute(ctxWithParams(Map.of()));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("parameters.url required");
    }

    @Test
    @DisplayName("目标地址非法时执行应失败, 并给出地址格式错误提示")
    void shouldReject_whenUrlMalformed() {
      TaskResult r = executor.execute(ctxWithParams(Map.of("url", "not a uri @@@")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not a valid URI");
    }

    @Test
    @DisplayName("地址缺少主机名时执行应失败, 避免请求落到本地文件等非网络资源")
    void shouldReject_whenUrlHasNoHost() {
      TaskResult r = executor.execute(ctxWithParams(Map.of("url", "file:///etc/passwd")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("URL must have host");
    }

    @Test
    @DisplayName("请求方法不在允许清单内时执行应失败, 并指出方法受限")
    void shouldReject_whenMethodOutsideAllowedMethods() {
      props.setAllowedMethods(Set.of("GET"));
      TaskResult r = executor.execute(
          ctxWithParams(Map.of("url", "http://api.example.com", "method", "DELETE")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not in allowedMethods");
    }

    @Test
    @DisplayName("期望状态码传入非数字时执行应失败, 提示取值类型不合法")
    void shouldReject_whenExpectStatusNotNumeric() {
      TaskResult r = executor.execute(
          ctxWithParams(Map.of("url", "http://api.example.com", "expectStatus", "not-a-number")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("expectStatus must be Integer or List");
    }

    @Test
    @DisplayName("请求体超过配置上限时执行应失败, 并在提示中带出上限值")
    void shouldReject_whenBodyExceedsConfiguredLimit() {
      props.setMaxRequestBodyBytes(4);
      TaskResult r = executor.execute(ctxWithParams(
          Map.of("url", "http://api.example.com", "method", "POST", "body", "12345")));

      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("maxRequestBodyBytes=4");
    }

    @Test
    @DisplayName("认证类型不在允许清单内时执行应失败, 提示类型受限")
    void shouldReject_whenAuthTypeNotAllowed() {
      TaskResult r = executor.execute(
          ctxWithParams(Map.of("url", "http://api.example.com", "auth", Map.of("type", "oauth"))));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not in allowedAuthTypes");
    }

    @Test
    @DisplayName("持有者认证缺少令牌时执行应失败, 提示令牌必填")
    void shouldReject_whenBearerTokenMissing() {
      TaskResult r = executor.execute(
          ctxWithParams(Map.of("url", "http://api.example.com", "auth", Map.of("type", "bearer"))));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("auth.token required");
    }

    @Test
    @DisplayName("参数顶层出现敏感凭据字段时应被凭据闸门拒绝, 并返回敏感数据错误码")
    void rejectsSensitiveCredentialInParameters_LaneC() {
      // 顶层 password 字段(非 auth 协议)直接拒
      TaskResult r = executor.execute(
          ctxWithParams(Map.of("url", "http://api.example.com", "myPassword", "leak")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("SENSITIVE_DATA_IN_PARAMETERS");
    }

    @Test
    @DisplayName("认证子树是协议显式字段, 凭据闸门应放行而不误报敏感数据")
    void allowsAuthSubtreeAsHttpProtocol_LaneC() {
      // auth.password / auth.token 是 HTTP executor 显式协议,允许通过 Lane C 闸门(继续按 protocol 走)
      TaskResult r = executor.execute(ctxWithParams(Map.of(
          "url", "http://api.example.com", "auth", Map.of("type", "bearer", "token", "tk"))));
      // 这里不要求成功(URL 是假的会走真实请求),只要 Lane C 不拦即可:错误信息不含 SENSITIVE
      assertThat(r.message()).doesNotContain("SENSITIVE_DATA_IN_PARAMETERS");
    }
  }

  // ─── Host black/whitelist ───────────────────────────────────────────────────

  @Nested
  @DisplayName("主机黑白名单: 默认封禁与白名单优先级的判定")
  class HostFiltering {

    @Test
    @DisplayName("云元数据地址进入封禁列表后, 请求应被拒绝并提示主机受限")
    void shouldBlock_whenHostIsMetadataService() {
      props.setBlockedHostPatterns(Set.of("169.254.169.254", "metadata.google.internal"));
      TaskResult r = executor.execute(
          ctxWithParams(Map.of("url", "http://169.254.169.254/latest/meta-data/")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("host blocked");
    }

    @Test
    @DisplayName("本机主机名与回环网段进入封禁列表后, 请求应被拒绝")
    void shouldBlock_whenHostIsLocalhost() {
      props.setBlockedHostPatterns(Set.of("localhost", "127.*"));
      TaskResult r = executor.execute(ctxWithParams(Map.of("url", "http://localhost:9999/foo")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("host blocked");
    }

    @Test
    @DisplayName("主机不匹配白名单通配模式时, 请求应在发起前被拒绝")
    void shouldReject_whenHostOutsideAllowedPatterns() {
      props.setAllowedHostPatterns(Set.of("*.example.com"));
      TaskResult r = executor.execute(ctxWithParams(Map.of("url", "http://api.evil.com/x")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not in allowedHostPatterns");
    }

    @Test
    @DisplayName("主机命中白名单模式时应通过校验, 后续失败仅来自网络不可达")
    void shouldPassHostCheck_whenHostMatchesAllowedPattern() {
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
    @DisplayName("同一主机同时命中白名单与黑名单时, 应以封禁优先拒绝")
    void shouldPreferBlocklist_whenHostBothAllowedAndBlocked() {
      props.setAllowedHostPatterns(Set.of("*"));
      props.setBlockedHostPatterns(Set.of("evil.com"));
      TaskResult r = executor.execute(ctxWithParams(Map.of("url", "http://evil.com/x")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("host blocked");
    }
  }

  // ─── Glob matching ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("主机通配匹配: 通配符分段语义")
  class GlobMatch {

    @Test
    @DisplayName("通配符位于中段时只匹配一层标签, 不跨点号贪婪匹配")
    void shouldMatchSingleSegment_whenWildcardInMiddle() {
      assertThat(HttpTaskExecutor.matchesGlob("api.*.com", "api.foo.com")).isTrue();
      assertThat(HttpTaskExecutor.matchesGlob("api.*.com", "api.foo.bar.com")).isFalse();
    }

    @Test
    @DisplayName("无通配符的主机模式应精确匹配, 域名后缀不同则不命中")
    void shouldMatchExactly_whenPatternHasNoWildcard() {
      assertThat(HttpTaskExecutor.matchesGlob("api.example.com", "api.example.com"))
          .isTrue();
      assertThat(HttpTaskExecutor.matchesGlob("api.example.com", "api.example.org"))
          .isFalse();
    }

    @Test
    @DisplayName("通配符前导模式应匹配子域, 但不匹配裸域本身")
    void shouldMatchSubdomains_whenPatternStartsWithWildcard() {
      assertThat(HttpTaskExecutor.matchesGlob("*.example.com", "foo.example.com"))
          .isTrue();
      assertThat(HttpTaskExecutor.matchesGlob("*.example.com", "example.com")).isFalse();
    }
  }

  // ─── Capability ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("执行器能力应反映配置: 任务类型为接口调用, 资源类型为网络且非幂等")
  void shouldExposeCapability_whenExecutorConfigured() {
    assertThat(executor.taskType()).isEqualTo("http");
    assertThat(executor.capability().resourceKinds()).containsExactly(ResourceKind.NET);
    assertThat(executor.capability().idempotent()).isFalse();
  }

  // ─── Real HTTP ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("真实请求链路: 状态码, 重试, 截断与重定向")
  class RealHttp {

    @Test
    @DisplayName("请求成功时应返回状态码与响应体原文")
    void shouldReturnBodyAndStatus_whenGetSucceeds() {
      server.createContext("/hello", ex -> {
        byte[] body = "world".getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
      });

      TaskResult r = executor.execute(ctxWithParams(Map.of("url", url("/hello"))));

      assertThat(r.success()).isTrue();
      assertThat(r.output()).containsEntry("statusCode", 200);
      assertThat(r.output()).containsEntry("responseBody", "world");
    }

    @Test
    @DisplayName("提交请求时应把请求体完整送达服务端, 且响应状态符合期望")
    void shouldSendBody_whenPostWithExpectedStatus() {
      AtomicInteger received = new AtomicInteger();
      server.createContext("/echo", ex -> {
        byte[] body = ex.getRequestBody().readAllBytes();
        received.set(body.length);
        ex.sendResponseHeaders(201, body.length);
        ex.getResponseBody().write(body);
        ex.close();
      });

      TaskResult r = executor.execute(ctxWithParams(Map.of(
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
    @DisplayName("实际状态码与期望不一致时执行应失败, 并在提示中带出实际值")
    void shouldFail_whenStatusNotExpected() {
      server.createContext("/notfound", ex -> {
        ex.sendResponseHeaders(404, -1);
        ex.close();
      });

      TaskResult r =
          executor.execute(ctxWithParams(Map.of("url", url("/notfound"), "expectStatus", 200)));

      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("status 404 not in expected");
    }

    @Test
    @DisplayName("配置基础认证时应在请求头注入对应凭据")
    void shouldInjectBasicAuthHeader_whenAuthConfigured() {
      AtomicInteger gotAuth = new AtomicInteger();
      server.createContext("/auth", ex -> {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h != null && h.startsWith("Basic ")) gotAuth.incrementAndGet();
        ex.sendResponseHeaders(200, -1);
        ex.close();
      });

      TaskResult r = executor.execute(ctxWithParams(Map.of(
          "url", url("/auth"), "auth", Map.of("type", "basic", "username", "u", "password", "p"))));

      assertThat(r.success()).isTrue();
      assertThat(gotAuth.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("配置持有者认证时应在请求头注入令牌")
    void shouldInjectBearerToken_whenAuthConfigured() {
      AtomicInteger gotBearer = new AtomicInteger();
      server.createContext("/bearer", ex -> {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer xyz".equals(h)) gotBearer.incrementAndGet();
        ex.sendResponseHeaders(200, -1);
        ex.close();
      });

      executor.execute(ctxWithParams(
          Map.of("url", url("/bearer"), "auth", Map.of("type", "bearer", "token", "xyz"))));

      assertThat(gotBearer.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("响应体超过上限时应截断到上限长度, 并标记已截断")
    void shouldTruncateResponse_whenBodyExceedsLimit() {
      props.setMaxResponseBytes(10);
      server.createContext("/big", ex -> {
        byte[] body = new byte[1000];
        java.util.Arrays.fill(body, (byte) 'X');
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
      });

      TaskResult r = executor.execute(ctxWithParams(Map.of("url", url("/big"))));

      assertThat(r.success()).isTrue();
      assertThat(((String) r.output().get("responseBody"))).hasSize(10);
      assertThat(r.output()).containsEntry("responseTruncated", true);
    }

    @Test
    @DisplayName("无响应体时应返回空字符串, 且不误标为已截断")
    void shouldReturnEmptyBody_whenNoContent() {
      // 空 body(sendResponseHeaders(200,-1) → 无内容):responseBody="" 且 truncated=false。
      server.createContext("/empty", ex -> {
        ex.sendResponseHeaders(204, -1);
        ex.close();
      });

      TaskResult r =
          executor.execute(ctxWithParams(Map.of("url", url("/empty"), "expectStatus", 204)));

      assertThat(r.success()).isTrue();
      assertThat(r.output()).containsEntry("responseBody", "");
      assertThat(r.output()).containsEntry("responseTruncated", false);
    }

    @Test
    @DisplayName("响应体超过上限时应保留最前面的固定字节, 且内容逐字节一致")
    void shouldKeepFirstBytes_whenBodyExceedsLimit() {
      // 响应恰好 max+N 字节:kept 必须是前 max 字节(逐字节等于原内容),truncated=true。
      props.setMaxResponseBytes(10);
      byte[] payload = "0123456789ABCDEFGHIJ".getBytes(StandardCharsets.UTF_8); // 20 字节
      server.createContext("/exact", ex -> {
        ex.sendResponseHeaders(200, payload.length);
        ex.getResponseBody().write(payload);
        ex.close();
      });

      TaskResult r = executor.execute(ctxWithParams(Map.of("url", url("/exact"))));

      assertThat(r.success()).isTrue();
      assertThat(r.output()).containsEntry("responseBody", "0123456789"); // 恰前 10 字节
      assertThat(r.output()).containsEntry("responseTruncated", true);
    }

    @Test
    @DisplayName("超大响应流下应有界读取并在读满上限后立即关闭, 防止内存膨胀")
    void shouldReadBoundedBytes_whenStreamIsHuge() throws IOException {
      // OOM 回归守护:有界读取的硬契约是客户端最多读取 max+1 字节并关闭流。
      // 服务端是否能在本机回环网络上感知 TCP 断连受内核/客户端缓冲影响,不是 HTTP API 可保证的语义。
      int max = 16;
      final long hugeTotal = 64L * 1024 * 1024; // 64 MiB
      java.util.concurrent.atomic.AtomicLong written = new java.util.concurrent.atomic.AtomicLong();
      java.util.concurrent.atomic.AtomicBoolean closed =
          new java.util.concurrent.atomic.AtomicBoolean();
      InputStream hugeStream = new InputStream() {
        private long remaining = hugeTotal;

        @Override
        public int read(byte[] buffer, int offset, int length) {
          if (remaining == 0) {
            return -1;
          }
          int count = (int) Math.min(length, remaining);
          java.util.Arrays.fill(buffer, offset, offset + count, (byte) 'Z');
          remaining -= count;
          written.addAndGet(count);
          return count;
        }

        @Override
        public int read() {
          byte[] oneByte = new byte[1];
          return read(oneByte, 0, 1) == -1 ? -1 : oneByte[0] & 0xff;
        }

        @Override
        public void close() {
          closed.set(true);
        }
      };

      byte[] read = HttpTaskExecutor.readBounded(hugeStream, max);

      assertThat(read).hasSize(max + 1).containsOnly((byte) 'Z');
      assertThat(written).hasValue(max + 1L);
      assertThat(closed).isTrue();
    }

    @Test
    @DisplayName("幂等请求遇到服务端错误时应重试, 直到成功并记录尝试次数")
    void shouldRetry_whenIdempotentRequestReturnsServerError() {
      props.setMaxRetries(2);
      props.setRetryBackoff(Duration.ofMillis(10));
      AtomicInteger calls = new AtomicInteger();
      server.createContext("/flaky", ex -> {
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
    @DisplayName("非幂等请求遇到服务端错误时不应重试, 避免重复副作用")
    void shouldNotRetry_whenNonIdempotentRequestReturnsServerError() {
      props.setMaxRetries(2);
      AtomicInteger calls = new AtomicInteger();
      server.createContext("/post-fail", ex -> {
        calls.incrementAndGet();
        ex.sendResponseHeaders(503, -1);
        ex.close();
      });

      executor.execute(ctxWithParams(Map.of("url", url("/post-fail"), "method", "POST")));

      // POST 不重试,只 1 次
      assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("重试耗尽后执行应失败, 并在提示中给出总尝试次数")
    void shouldReportAttemptCount_whenAllRetriesExhausted() {
      // GET 幂等:连接持续失败(I/O 异常)→ 用尽 maxRetries+1 次,fail message 含次数。
      // 用一个保留为不可路由的端口(server 已起但 path 未注册不会触发 I/O 失败,故指向已关端口)。
      props.setMaxRetries(2);
      props.setRetryBackoff(Duration.ofMillis(5));
      props.setDefaultTimeout(Duration.ofMillis(200));
      // 把 server 停掉,使每次连接都被拒(ConnectException)。
      server.stop(0);
      server = null;

      TaskResult r =
          executor.execute(ctxWithParams(Map.of("url", "http://127.0.0.1:" + serverPort + "/x")));

      assertThat(r.success()).isFalse();
      // maxRetries=2 → 首次 + 2 次重试 = 3 次尝试,fail message 报次数。
      assertThat(r.message()).contains("http failed after 3 attempts");
    }

    @Test
    @DisplayName("服务端跳转到内网地址时不应跟随, 直接返回原始跳转状态")
    void shouldNotFollowRedirect_whenLocationPointsToInternalHost() {
      // SSRF 加固:服务端 301 指向内网/metadata,执行器禁止跟随 → 直接拿到 30x,不打内网。
      server.createContext("/redirect", ex -> {
        ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
        ex.sendResponseHeaders(301, -1);
        ex.close();
      });

      TaskResult r =
          executor.execute(ctxWithParams(Map.of("url", url("/redirect"), "expectStatus", 301)));

      // followRedirects=NEVER:返回 301 本身(未跟随到内网);expectStatus=301 命中 → 不跟随得证。
      assertThat(r.success()).isTrue();
      assertThat(r.output()).containsEntry("statusCode", 301);
    }
  }

  // ─── P2-3: 响应头脱敏 ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("响应头脱敏: 敏感头在落库前被替换")
  class ResponseHeaderRedaction {

    @Test
    @DisplayName("写回结果时应对会话与认证类响应头脱敏, 非敏感头仍透传")
    void shouldRedactSensitiveHeaders_whenWritingOutput() {
      // P2-3(2026-06-03):出口响应若回声 Set-Cookie / Authorization,落 task_result.output
      // 会形成 forensic 期间凭据泄漏。executor 在写 output 前先按固定黑名单脱敏(case-insensitive)。
      server.createContext("/echo", ex -> {
        ex.getResponseHeaders().add("Set-Cookie", "SESSION=secret-value; HttpOnly");
        ex.getResponseHeaders().add("Authorization", "Bearer downstream-token");
        ex.getResponseHeaders().add("X-Trace-Id", "trace-123");
        ex.sendResponseHeaders(200, -1);
        ex.close();
      });

      TaskResult r = executor.execute(ctxWithParams(Map.of("url", url("/echo"))));

      assertThat(r.success()).isTrue();
      @SuppressWarnings("unchecked")
      Map<String, java.util.List<String>> hdrs =
          (Map<String, java.util.List<String>>) r.output().get("responseHeaders");
      // Set-Cookie / Authorization 必须被脱敏(任何大小写形态都不应残留原值)
      hdrs.forEach((name, values) -> {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("set-cookie") || lower.equals("authorization")) {
          assertThat(values).containsExactly("[REDACTED]");
        }
      });
      // 非敏感头透传(用于业务可观测性)
      boolean traceFound =
          hdrs.entrySet().stream().anyMatch(e -> e.getKey().equalsIgnoreCase("X-Trace-Id"));
      assertThat(traceFound).isTrue();
    }

    @Test
    @DisplayName("脱敏规则应忽略大小写, 并覆盖代理认证与旧版会话头")
    void shouldRedactSensitiveHeaders_whenCaseDiffers() {
      // 直接驱动静态 helper,避免 HTTP server 编排成本;case-insensitive 全覆盖。
      Map<String, java.util.List<String>> in = new java.util.LinkedHashMap<>();
      in.put("Set-Cookie", java.util.List.of("s=1"));
      in.put("set-cookie2", java.util.List.of("legacy"));
      in.put("AUTHORIZATION", java.util.List.of("Bearer abc"));
      in.put("Proxy-Authorization", java.util.List.of("Basic xx"));
      in.put("cookie", java.util.List.of("a=b"));
      in.put("X-Trace-Id", java.util.List.of("t1"));

      Map<String, java.util.List<String>> out = HttpTaskExecutor.sanitizeResponseHeaders(in);

      assertThat(out)
          .containsEntry("Set-Cookie", java.util.List.of("[REDACTED]"))
          .containsEntry("set-cookie2", java.util.List.of("[REDACTED]"))
          .containsEntry("AUTHORIZATION", java.util.List.of("[REDACTED]"))
          .containsEntry("Proxy-Authorization", java.util.List.of("[REDACTED]"))
          .containsEntry("cookie", java.util.List.of("[REDACTED]"))
          .containsEntry("X-Trace-Id", java.util.List.of("t1"));
    }

    @Test
    @DisplayName("响应头为空或缺失时应返回空结果而不是直接报错")
    void shouldReturnEmpty_whenHeadersNullOrEmpty() {
      assertThat(HttpTaskExecutor.sanitizeResponseHeaders(null)).isEmpty();
      assertThat(HttpTaskExecutor.sanitizeResponseHeaders(Map.of())).isEmpty();
    }
  }

  // ─── enforce allowlist (deny-all) ────────────────────────────────────────────

  @Nested
  @DisplayName("白名单强制模式: 空名单下的默认拒绝")
  class EnforceAllowlist {

    @Test
    @DisplayName("开启强制模式且白名单为空时应拒绝所有请求, 默认拒绝而非默认放行")
    void shouldDenyAll_whenAllowlistEmptyAndEnforced() {
      // enforceAllowlist=true + 空 allowedHostPatterns → fail-closed 拒绝全部。
      props.setEnforceAllowlist(true);
      props.setAllowedHostPatterns(Set.of());

      TaskResult r = executor.execute(ctxWithParams(Map.of("url", url("/anything"))));

      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("deny all");
    }
  }
}
