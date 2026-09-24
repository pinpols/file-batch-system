package io.github.pinpols.batch.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
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
import mockwebserver3.SocketPolicy;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link PlatformHttpClient} 真 HTTP 测试使用 JDK {@link HttpServer} 或 MockWebServer 启动 stub。 */
class PlatformHttpClientTest {

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
  void registerReturnsResponse() throws IOException {
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
        newClient().register(Map.of("workerCode", "w-1"));

    assertThat(resp.id()).isEqualTo(123L);
    assertThat(resp.workerCode()).isEqualTo("w-1");
    assertThat(seenAuth.get()).isEqualTo("test-key");
    assertThat(seenTenant.get()).isEqualTo("tx");
  }

  @Test
  void claimIncludesIdempotencyKey() throws IOException {
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
  void non2xxThrowsWithoutErrBodyLeak() {
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
  void deactivateCallsWorkerPath() throws IOException {
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
  void reportEmptyResponseOk() throws IOException {
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
  void transportPolicyEnablesHappyEyeballsWithoutImplicitPostRetry() {
    OkHttpClient client = PlatformHttpClient.createHttpClient(Duration.ofSeconds(2));

    assertThat(client.fastFallback()).isTrue();
    assertThat(client.retryOnConnectionFailure()).isFalse();
    assertThat(client.followRedirects()).isFalse();
    assertThat(client.followSslRedirects()).isFalse();
  }

  @Test
  void fallsBackFromIpv6BlackholeToIpv4WithoutDuplicatePost() throws Exception {
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
          platformClient.register(Map.of("workerCode", "w-1"));
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

      assertThat(response.id()).isEqualTo(123L);
      assertThat(socketFactory.attemptedAddresses())
          .containsSubsequence(unreachableIpv6, reachableIpv4);
      assertThat(elapsedMillis).isBetween(150L, 2_500L);
      assertThat(ipv4Server.getRequestCount()).isEqualTo(1);
    }
  }

  @Test
  void cancelInFlightCallsUnblocksHangingSynchronousRequest() throws Exception {
    try (MockWebServer hangingServer = new MockWebServer()) {
      hangingServer.enqueue(new MockResponse.Builder()
          .socketPolicy(SocketPolicy.NoResponse.INSTANCE)
          .build());
      hangingServer.start(InetAddress.getByName("127.0.0.1"), 0);
      PlatformHttpClient client =
          new PlatformHttpClient(config("http://127.0.0.1:" + hangingServer.getPort()));
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread requestThread = new Thread(() -> {
        try {
          client.register(Map.of("workerCode", "w-1"));
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
  void deactivateHonorsRemainingShutdownBudget() throws Exception {
    try (MockWebServer hangingServer = new MockWebServer()) {
      hangingServer.enqueue(new MockResponse.Builder()
          .socketPolicy(SocketPolicy.NoResponse.INSTANCE)
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
  void realSocketHappyEyeballsMatrix() throws Exception {
    String matrixFile = System.getenv("BATCH_SDK_HE_MATRIX_FILE");
    Assumptions.assumeTrue(matrixFile != null && !matrixFile.isBlank());
    JsonNode scenarios = SdkJsonMapperFactory.create()
        .readTree(Files.readString(Path.of(matrixFile)))
        .get("scenarios");
    Iterator<Map.Entry<String, JsonNode>> iterator = scenarios.fields();
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
