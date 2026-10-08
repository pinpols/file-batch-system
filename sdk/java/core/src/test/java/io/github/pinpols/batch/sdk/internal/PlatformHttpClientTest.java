package io.github.pinpols.batch.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.wire.RegisterRequest;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.net.SocketFactory;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link PlatformHttpClient} 真 HTTP 测试使用 JDK {@link HttpServer} 或 MockWebServer 启动 stub。 */
@DisplayName("PlatformHttpClient — 真实 HTTP 请求、传输策略与取消行为")
class PlatformHttpClientTest {

  @Test
  @DisplayName("类型化注册使用真实传输序列化，时间字符串与旧 Map 一致且可选字段省略")
  void shouldPreserveLegacyRegistrationJson_whenUsingTypedRequest() throws Exception {
    try (MockWebServer registrationServer = new MockWebServer()) {
      registrationServer.enqueue(new MockResponse.Builder().body("{\"id\":123}").build());
      registrationServer.start(InetAddress.getByName("127.0.0.1"), 0);
      PlatformHttpClient client =
          new PlatformHttpClient(config("http://127.0.0.1:" + registrationServer.getPort()));
      Instant heartbeat = Instant.parse("2026-10-08T01:02:03.123456789Z");
      RegisterRequest request = RegisterRequest.builder()
          .tenantId("tx")
          .workerCode("w-1")
          .workerGroup("sdk-self-hosted")
          .status("RUNNING")
          .heartbeatAt(heartbeat)
          .capabilityTags(List.of("IMPORT"))
          .currentLoad(0)
          .maxConcurrent(4)
          .protocolVersion(RegisterRequest.CURRENT_PROTOCOL_VERSION)
          .build();

      assertThat(client.register(request).id()).isEqualTo(123L);
      RecordedRequest captured = registrationServer.takeRequest(1, TimeUnit.SECONDS);
      assertThat(captured).isNotNull();
      JsonNode actual =
          SdkJsonMapperFactory.create().readTree(captured.getBody().utf8());
      assertThat(actual.path("tenantId").asText()).isEqualTo("tx");
      assertThat(actual.path("workerCode").asText()).isEqualTo("w-1");
      assertThat(actual.path("workerGroup").asText()).isEqualTo("sdk-self-hosted");
      assertThat(actual.path("status").asText()).isEqualTo("RUNNING");
      assertThat(actual.path("heartbeatAt").asText()).isEqualTo(heartbeat.toString());
      assertThat(actual.path("capabilityTags").get(0).asText()).isEqualTo("IMPORT");
      assertThat(actual.path("currentLoad").asInt()).isZero();
      assertThat(actual.path("maxConcurrent").asInt()).isEqualTo(4);
      assertThat(actual.path("protocolVersion").asText())
          .isEqualTo(RegisterRequest.CURRENT_PROTOCOL_VERSION);
      assertThat(actual.has("hostName")).isFalse();
      assertThat(actual.has("taskTypes")).isFalse();
      assertThat(captured.getHeaders().get("X-Batch-Tenant-Id")).isEqualTo("tx");
      client.evictIdleConnections();
    }
  }

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

  private PlatformHttpClient newClient() {
    return new PlatformHttpClient(config("http://127.0.0.1:" + port));
  }

  private static RegisterRequest registrationRequest() {
    return RegisterRequest.builder()
        .tenantId("tx")
        .workerCode("w-1")
        .workerGroup("sdk-self-hosted")
        .status("RUNNING")
        .build();
  }

  private static BatchPlatformClientConfig config(String baseUrl) {
    return BatchPlatformClientConfig.builder()
        .baseUrl(baseUrl)
        .apiKey("test-key")
        .tenantId("tx")
        .workerCode("w-1")
        .kafkaBootstrap("kafka:9092")
        .kafkaTopicPattern("p.*")
        .kafkaGroupId("g")
        .httpTimeout(Duration.ofSeconds(2))
        .build();
  }

  @Test
  @DisplayName("注册成功后返回平台下发的 worker 标识与编码,并带上鉴权与租户请求头")
  void shouldReturnWorkerRegistration_whenRegisterSucceeds() throws IOException {
    AtomicReference<String> seenAuth = new AtomicReference<>();
    AtomicReference<String> seenTenant = new AtomicReference<>();
    server.createContext("/internal/workers/register", ex -> {
      seenAuth.set(ex.getRequestHeaders().getFirst("X-Batch-Api-Key"));
      seenTenant.set(ex.getRequestHeaders().getFirst("X-Batch-Tenant-Id"));
      byte[] body = ("{\"id\":123,\"tenantId\":\"tx\",\"workerCode\":\"w-1\",\"status\":\"ONLINE\","
              + "\"workerGroup\":\"sdk-self-hosted\",\"futureField\":true}")
          .getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });

    PlatformHttpClient.WorkerRegistrationResponse resp =
        newClient().register(registrationRequest());

    assertThat(resp.id()).isEqualTo(123L);
    assertThat(resp.workerCode()).isEqualTo("w-1");
    assertThat(seenAuth.get()).isEqualTo("test-key");
    assertThat(seenTenant.get()).isEqualTo("tx");
  }

  @Test
  @DisplayName("认领任务时请求携带幂等键")
  void shouldSendIdempotencyKey_whenClaiming() throws IOException {
    AtomicReference<String> seenIdem = new AtomicReference<>();
    server.createContext("/internal/tasks/42/claim", ex -> {
      seenIdem.set(ex.getRequestHeaders().getFirst("Idempotency-Key"));
      ex.sendResponseHeaders(200, -1);
      ex.close();
    });

    newClient().claim(42L, "idem-xyz", Map.of("workerCode", "w-1"));

    assertThat(seenIdem.get()).isEqualTo("idem-xyz");
  }

  @Test
  @DisplayName("非成功响应抛错且不回显错误响应体,避免泄漏敏感字段")
  void shouldThrowWithoutLeakingBody_whenStatusNotSuccessful() {
    server.createContext("/internal/workers/w-1/heartbeat", ex -> {
      // errBody 含潜在敏感字段(token / 内部错误码),不应出现在 exception message
      byte[] body = "{\"code\":\"FORBIDDEN\",\"detail\":\"token=secret-abc\"}"
          .getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(403, body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });

    assertThatThrownBy(() -> newClient().heartbeat("w-1", Map.of()))
        .isInstanceOf(PlatformHttpException.class)
        .hasMessageContaining("HTTP 403")
        .hasMessageContaining("/internal/workers/w-1/heartbeat")
        .hasMessageNotContaining("FORBIDDEN")
        .hasMessageNotContaining("secret-abc")
        .hasMessageNotContaining("body=");
  }

  @Test
  @DisplayName("注销请求命中指定 worker 的注销路径")
  void shouldCallDeactivatePath_whenDeactivating() throws IOException {
    AtomicReference<String> seenPath = new AtomicReference<>();
    server.createContext("/internal/workers/w-1/deactivate", ex -> {
      seenPath.set(ex.getRequestURI().getPath());
      ex.sendResponseHeaders(204, -1);
      ex.close();
    });
    newClient().deactivate("w-1", Map.of("tenantId", "tx"));
    assertThat(seenPath.get()).isEqualTo("/internal/workers/w-1/deactivate");
  }

  @Test
  @DisplayName("任务回报无响应体也视为成功")
  void shouldAcceptEmptyBody_whenReporting() throws IOException {
    AtomicReference<String> seenPath = new AtomicReference<>();
    server.createContext("/internal/tasks/99/report", ex -> {
      seenPath.set(ex.getRequestURI().getPath());
      ex.sendResponseHeaders(204, -1);
      ex.close();
    });
    newClient().report(99L, "idem", Map.of("success", true));

    assertThat(seenPath.get()).isEqualTo("/internal/tasks/99/report");
  }

  @Test
  @DisplayName("传输客户端开启多地址竞速回退,同时关闭连接重试与重定向跟随")
  void shouldEnableFastFallbackAndDisableRetries_whenBuildingClient() {
    OkHttpClient client = PlatformHttpClient.createHttpClient(Duration.ofSeconds(2));

    assertThat(client.fastFallback()).isTrue();
    assertThat(client.retryOnConnectionFailure()).isFalse();
    assertThat(client.followRedirects()).isFalse();
    assertThat(client.followSslRedirects()).isFalse();
  }

  @Test
  @DisplayName("首选地址黑洞时回退到可达地址,且请求只发送一次不重复提交")
  void shouldFallBackToReachableAddress_whenFirstAddressBlackholed() throws Exception {
    InetAddress unreachableIpv6 = InetAddress.getByName("2001:db8::1");
    InetAddress reachableIpv4 = InetAddress.getByName("127.0.0.1");
    FaultInjectingSocketFactory socketFactory = new FaultInjectingSocketFactory(unreachableIpv6);
    try (MockWebServer ipv4Server = new MockWebServer()) {
      ipv4Server.enqueue(new MockResponse.Builder()
          .body("{\"id\":123,\"tenantId\":\"tx\",\"workerCode\":\"w-1\",\"status\":\"ONLINE\"}")
          .build());
      ipv4Server.start(reachableIpv4, 0);
      OkHttpClient client = PlatformHttpClient.createHttpClient(Duration.ofSeconds(4))
          .newBuilder()
          .socketFactory(socketFactory)
          .dns(hostname -> List.of(unreachableIpv6, reachableIpv4))
          .build();
      PlatformHttpClient platformClient =
          new PlatformHttpClient(config("http://dual-stack.test:" + ipv4Server.getPort()), client);

      long startedAt = System.nanoTime();
      PlatformHttpClient.WorkerRegistrationResponse response =
          platformClient.register(registrationRequest());
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

      assertThat(response.id()).isEqualTo(123L);
      assertThat(socketFactory.attemptedAddresses())
          .containsSubsequence(unreachableIpv6, reachableIpv4);
      assertThat(elapsedMillis).isBetween(150L, 2_500L);
      assertThat(ipv4Server.getRequestCount()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("取消在飞请求后挂起的同步调用立即中止并抛连接异常")
  void shouldUnblockHangingRequest_whenCancellingInFlightCalls() throws Exception {
    try (MockWebServer hangingServer = new MockWebServer()) {
      hangingServer.enqueue(new MockResponse.Builder()
          .onResponseStart(SocketEffect.Stall.INSTANCE)
          .build());
      hangingServer.start(InetAddress.getByName("127.0.0.1"), 0);
      PlatformHttpClient client =
          new PlatformHttpClient(config("http://127.0.0.1:" + hangingServer.getPort()));
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread requestThread = new Thread(() -> {
        try {
          client.register(registrationRequest());
        } catch (Throwable throwable) {
          failure.set(throwable);
        }
      });

      requestThread.start();
      assertThat(hangingServer.takeRequest(1, TimeUnit.SECONDS)).isNotNull();

      client.cancelInFlightCalls();
      requestThread.join(1_000L);

      assertThat(requestThread.isAlive()).isFalse();
      assertThat(failure.get()).isInstanceOf(IOException.class);
      client.evictIdleConnections();
    }
  }

  @Test
  @DisplayName("注销请求遵守剩余关停预算,超时即中止")
  void shouldRespectShutdownBudget_whenDeactivating() throws Exception {
    try (MockWebServer hangingServer = new MockWebServer()) {
      hangingServer.enqueue(new MockResponse.Builder()
          .onResponseStart(SocketEffect.Stall.INSTANCE)
          .build());
      hangingServer.start(InetAddress.getByName("127.0.0.1"), 0);
      PlatformHttpClient client =
          new PlatformHttpClient(config("http://127.0.0.1:" + hangingServer.getPort()));

      long startedAt = System.nanoTime();
      assertThatThrownBy(() -> client.deactivate("w-1", Map.of(), Duration.ofMillis(100)))
          .isInstanceOf(IOException.class);
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

      assertThat(elapsedMillis).isLessThan(1_000L);
      client.evictIdleConnections();
    }
  }

  @Test
  @DisplayName("提供场景矩阵文件时逐条验证地址竞速结果与耗时上限")
  void shouldValidateScenarioMatrix_whenMatrixFileProvided() throws Exception {
    String matrixFile = System.getenv("BATCH_SDK_HE_MATRIX_FILE");
    Assumptions.assumeTrue(matrixFile != null && !matrixFile.isBlank());
    JsonNode scenarios = SdkJsonMapperFactory.create()
        .readTree(Files.readString(Path.of(matrixFile)))
        .get("scenarios");
    Iterator<Map.Entry<String, JsonNode>> iterator = scenarios.properties().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, JsonNode> entry = iterator.next();
      String scenarioName = entry.getKey();
      JsonNode scenario = entry.getValue();
      List<InetAddress> addresses = new ArrayList<>();
      scenario.get("addresses").forEach(node -> {
        try {
          addresses.add(InetAddress.getByName(node.asText()));
        } catch (IOException exception) {
          throw new IllegalStateException(exception);
        }
      });
      Duration timeout = Duration.ofMillis(scenario.get("timeout_ms").asLong());
      BatchPlatformClientConfig scenarioConfig =
          config(scenario.get("base_url").asText()).toBuilder()
              .httpTimeout(timeout)
              .build();
      OkHttpClient okHttpClient = PlatformHttpClient.createHttpClient(timeout)
          .newBuilder()
          .dns(hostname -> List.copyOf(addresses))
          .build();
      PlatformHttpClient client = new PlatformHttpClient(scenarioConfig, okHttpClient);

      long startedAt = System.nanoTime();
      if ("success".equals(scenario.get("expected").asText())) {
        assertThatCode(() -> client.heartbeat("w-1", Map.of("tenantId", "tx")))
            .as(scenarioName)
            .doesNotThrowAnyException();
      } else {
        assertThatThrownBy(() -> client.heartbeat("w-1", Map.of("tenantId", "tx")))
            .as(scenarioName)
            .isInstanceOf(IOException.class);
      }
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
      assertThat(elapsedMillis).as(scenarioName).isLessThan(2_500L);
      // OkHttp 5 会按 IPv6/IPv4 交错并固定从 IPv6 开始；只有 IPv6 黑洞场景必然等待
      // fallback delay。IPv4 黑洞 + IPv6 正常应直接命中 IPv6，不能误判成未验证。
      if ("ipv6_blackhole".equals(scenarioName)) {
        assertThat(elapsedMillis).as(scenarioName).isGreaterThanOrEqualTo(150L);
      }
      client.evictIdleConnections();
    }
  }

  /** 让首个地址的 connect 持续挂起，直到 OkHttp 竞速成功后取消该 socket。 */
  private static final class FaultInjectingSocketFactory extends SocketFactory {
    private final InetAddress unreachableAddress;
    private final List<InetAddress> attemptedAddresses = new CopyOnWriteArrayList<>();

    private FaultInjectingSocketFactory(InetAddress unreachableAddress) {
      this.unreachableAddress = unreachableAddress;
    }

    private List<InetAddress> attemptedAddresses() {
      return List.copyOf(attemptedAddresses);
    }

    @Override
    public Socket createSocket() {
      return new FaultInjectingSocket(unreachableAddress, attemptedAddresses);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
      return SocketFactory.getDefault().createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
        throws IOException {
      return SocketFactory.getDefault().createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
      return SocketFactory.getDefault().createSocket(host, port);
    }

    @Override
    public Socket createSocket(
        InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
      return SocketFactory.getDefault().createSocket(address, port, localAddress, localPort);
    }
  }

  private static final class FaultInjectingSocket extends Socket {
    private static final long POLL_INTERVAL_MILLIS = 10L;

    private final InetAddress unreachableAddress;
    private final List<InetAddress> attemptedAddresses;

    private FaultInjectingSocket(
        InetAddress unreachableAddress, List<InetAddress> attemptedAddresses) {
      this.unreachableAddress = unreachableAddress;
      this.attemptedAddresses = attemptedAddresses;
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
      InetSocketAddress target = (InetSocketAddress) endpoint;
      attemptedAddresses.add(target.getAddress());
      if (!unreachableAddress.equals(target.getAddress())) {
        super.connect(endpoint, timeout);
        return;
      }

      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
      while (!isClosed() && System.nanoTime() < deadline) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MILLIS));
        if (Thread.currentThread().isInterrupted()) {
          throw new SocketException("fault-injected connect interrupted");
        }
      }
      if (isClosed()) {
        throw new SocketException("fault-injected connect canceled");
      }
      throw new SocketTimeoutException("fault-injected connect timed out");
    }
  }
}
