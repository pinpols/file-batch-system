package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.worker.dispatchs.config.HttpDispatchChannelProperties;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HTTP 分发幂等请求")
class HttpDispatchChannelIdempotencyTest {

  private HttpServer server;
  private ExecutorService serverExecutor;
  private HttpDispatchChannelAdapter adapter;
  private final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
  private final Map<String, String> acceptedResults = new ConcurrentHashMap<>();
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicInteger sideEffects = new AtomicInteger();
  private final AtomicInteger responseStatus = new AtomicInteger(200);
  private final CountDownLatch releaseFirstResponse = new CountDownLatch(1);

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverExecutor = Executors.newCachedThreadPool();
    server.setExecutor(serverExecutor);
    server.createContext("/dispatch", exchange -> {
      String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
      idempotencyKeys.add(idempotencyKey);
      String acceptedResult = acceptedResults.computeIfAbsent(
          idempotencyKey, key -> "accepted-" + sideEffects.incrementAndGet());
      int requestNumber = requests.incrementAndGet();
      if (requestNumber == 1) {
        try {
          releaseFirstResponse.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
      byte[] body = acceptedResult.getBytes(StandardCharsets.UTF_8);
      try {
        exchange.sendResponseHeaders(responseStatus.get(), body.length);
        exchange.getResponseBody().write(body);
      } catch (IOException ignored) {
        // 第一轮故意超过客户端读取期限，客户端断开是本用例的故障注入。
      } finally {
        exchange.close();
      }
    });
    server.start();

    HttpDispatchChannelProperties properties = new HttpDispatchChannelProperties();
    properties.setConnectTimeoutMillis(500);
    properties.setReadTimeoutMillis(100);
    properties.setWriteTimeoutMillis(500);
    properties.setCallTimeoutMillis(700);
    BatchSecurityProperties securityProperties = new BatchSecurityProperties();
    securityProperties.setBypassMode(true);
    adapter = new HttpDispatchChannelAdapter(properties, securityProperties);
  }

  @AfterEach
  void tearDown() {
    adapter.shutdown();
    server.stop(0);
    serverExecutor.shutdownNow();
  }

  @Test
  @DisplayName("接收端处理后响应超时,重投仍使用同一幂等键")
  void dispatch_reusesIdempotencyKeyAfterResponseTimeout() {
    DispatchCommand command = command("dispatch-identity-1", true);

    DispatchResult firstAttempt = adapter.dispatch(command);
    releaseFirstResponse.countDown();
    DispatchResult retryAttempt = adapter.dispatch(command);

    assertThat(firstAttempt.success()).isFalse();
    assertThat(firstAttempt.externalOutcomeUnknown()).isFalse();
    assertThat(retryAttempt.success()).isTrue();
    assertThat(requests).hasValue(2);
    assertThat(sideEffects).as("接收端按幂等键复用首次处理结果，不重复产生业务副作用").hasValue(1);
    String expectedKey = UUID.nameUUIDFromBytes(
            "dispatch-identity-1".getBytes(StandardCharsets.UTF_8))
        .toString();
    assertThat(idempotencyKeys).containsExactly(expectedKey, expectedKey);
  }

  @Test
  @DisplayName("接收端已产生副作用后返回错误响应时标记外部结果未知")
  void dispatch_marksOutcomeUnknownWhenReceiverRespondsWithFailureAfterSideEffect() {
    responseStatus.set(503);

    DispatchResult result = adapter.dispatch(command());

    assertThat(result.success()).isFalse();
    assertThat(result.externalOutcomeUnknown()).isTrue();
    assertThat(requests).hasValue(1);
    assertThat(sideEffects).hasValue(1);
  }

  @Test
  @DisplayName("显式声明接收端幂等契约后错误响应可以进入受限自动重试")
  void dispatch_keepsKnownFailureWhenReceiverIdempotencyIsDeclared() {
    responseStatus.set(503);

    DispatchResult result = adapter.dispatch(command("dispatch-identity-1", true));

    assertThat(result.success()).isFalse();
    assertThat(result.externalOutcomeUnknown()).isFalse();
    assertThat(requests).hasValue(1);
    assertThat(sideEffects).hasValue(1);
  }

  @Test
  @DisplayName("超时后没有接收端幂等声明时结果未知")
  void dispatch_marksOutcomeUnknownAfterTimeoutWithoutReceiverIdempotencyDeclaration() {
    DispatchResult result = adapter.dispatch(command());
    releaseFirstResponse.countDown();

    assertThat(result.success()).isFalse();
    assertThat(result.externalOutcomeUnknown()).isTrue();
    assertThat(requests).hasValue(1);
    assertThat(sideEffects).hasValue(1);
  }

  @Test
  @DisplayName("自定义请求号含控制字符时不会注入 HTTP 幂等请求头")
  void dispatch_hashesCustomExternalRequestIdForHeader() {
    adapter.dispatch(command("business\r\nrequest"));

    assertThat(idempotencyKeys).hasSize(1);
    assertThat(idempotencyKeys.getFirst()).doesNotContain("\r", "\n");
    assertThat(idempotencyKeys.getFirst()).hasSize(36);
  }

  private DispatchCommand command() {
    return command("dispatch-identity-1");
  }

  private DispatchCommand command(String externalRequestId) {
    String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/dispatch";
    Map<String, Object> channelConfig = Map.of(
        "channel_type", "API",
        "target_endpoint", endpoint);
    return command(externalRequestId, endpoint, channelConfig);
  }

  private DispatchCommand command(String externalRequestId, boolean receiverIdempotencySupported) {
    String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/dispatch";
    Map<String, Object> channelConfig = Map.of(
        "channel_type", "API",
        "target_endpoint", endpoint,
        "receiver_idempotency_supported", receiverIdempotencySupported);
    return command(externalRequestId, endpoint, channelConfig);
  }

  private DispatchCommand command(
      String externalRequestId, String endpoint, Map<String, Object> channelConfig) {
    DispatchPayload payload = new DispatchPayload(
        "10", null, "CH1", "target", externalRequestId, null, null, null, null, null);
    return new DispatchCommand(
        "tenant-a",
        "trace-1",
        Map.of("id", 10L),
        channelConfig,
        payload);
  }
}
