package io.github.pinpols.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.handler.atomic.HttpAtomicConfig;
import io.github.pinpols.batch.sdk.handler.atomic.HttpAtomicHandler;
import io.github.pinpols.batch.sdk.idempotent.Idempotent;
import io.github.pinpols.batch.sdk.idempotent.SdkIdempotencyEntity;
import io.github.pinpols.batch.sdk.idempotent.SdkIdempotencyStore;
import io.github.pinpols.batch.sdk.internal.PlatformHttpClient;
import io.github.pinpols.batch.sdk.internal.SdkJsonMapperFactory;
import io.github.pinpols.batch.sdk.retry.RetryOn;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskDispatcher 非幂等 HTTP 调用 — 持久开始确认与安全请求边界")
class TaskDispatcherNonIdempotentStartTest {

  private final List<String> events = new CopyOnWriteArrayList<>();
  private final AtomicReference<JsonNode> startBody = new AtomicReference<>();
  private final AtomicReference<JsonNode> reportBody = new AtomicReference<>();
  private final AtomicReference<Map<String, Object>> frozenParameters =
      new AtomicReference<>(Map.of());
  private final AtomicReference<String> effectIdempotencyKey = new AtomicReference<>();
  private final AtomicInteger effectCount = new AtomicInteger();
  private final AtomicBoolean omitClaimInvocation = new AtomicBoolean();
  private final AtomicBoolean disconnectAfterEffect = new AtomicBoolean();
  private final AtomicBoolean replaceOwnerAfterClaim = new AtomicBoolean();
  private final AtomicReference<String> currentWorker = new AtomicReference<>("w-1");
  private final AtomicReference<String> currentInvocation =
      new AtomicReference<>("inv-claim-current");
  private final AtomicInteger observedStartStatus = new AtomicInteger();
  private final AtomicReference<String> startMethod = new AtomicReference<>();
  private HttpServer server;
  private PlatformHttpClient platform;
  private TaskDispatcher dispatcher;
  private String baseUrl;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handleRequest);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    try {
      if (dispatcher != null) {
        dispatcher.stop(Duration.ofMillis(100));
      }
    } finally {
      if (platform != null) {
        platform.evictIdleConnections();
      }
      if (server != null) {
        server.stop(0);
      }
    }
  }

  @Test
  @DisplayName("无幂等键 POST 在实际请求前确认开始，开始与回报均使用 CLAIM 当前调用标识")
  void shouldConfirmStartBeforePost_usingAuthoritativeClaimInvocation() {
    frozenParameters.set(parameters("POST", Map.of()));
    TaskDispatchMessage message = message(Map.of("partitionInvocationId", "inv-stale-message"));

    dispatch(message);

    assertThat(events).containsExactly("CLAIM", "START", "EFFECT", "REPORT");
    JsonNode marker = startBody.get();
    assertThat(marker).isNotNull();
    assertThat(startMethod.get()).isEqualTo("POST");
    assertThat(marker.path("tenantId").asText()).isEqualTo("tx");
    assertThat(marker.path("workerId").asText()).isEqualTo("w-1");
    assertThat(marker.path("partitionInvocationId").asText()).isEqualTo("inv-claim-current");
    JsonNode report = reportBody.get();
    assertThat(report.path("partitionInvocationId").asText()).isEqualTo("inv-claim-current");
    assertThat(report.path("success").asBoolean()).isTrue();
    assertThat(effectCount.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("开始端点拒绝已失去所有权的调用时不执行 POST，也不伪造失败回报")
  void shouldNotInvokePost_whenStartConfirmationIsRejected() {
    frozenParameters.set(parameters("POST", Map.of()));
    replaceOwnerAfterClaim.set(true);

    dispatch(message(Map.of()));

    assertThat(effectCount.get()).isZero();
    assertThat(events).containsExactly("CLAIM", "START");
    assertThat(observedStartStatus.get()).isEqualTo(409);
    assertThat(currentWorker.get()).isEqualTo("w-replacement");
    assertThat(currentInvocation.get()).isEqualTo("inv-replacement");
    assertThat(reportBody.get()).isNull();
  }

  @Test
  @DisplayName("GET 保留平台既有可恢复语义，不写非幂等开始标记")
  void shouldKeepGetReplaySafe_withoutNonIdempotentStart() {
    frozenParameters.set(parameters("GET", Map.of()));

    dispatch(message(Map.of()));

    assertThat(events).containsExactly("CLAIM", "EFFECT", "REPORT");
    assertThat(startBody.get()).isNull();
    assertThat(effectCount.get()).isEqualTo(1);
    assertThat(reportBody.get().path("success").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("冻结参数中带幂等键的 POST 保留可恢复语义，键原样传到外部服务")
  void shouldKeepPostReplaySafe_whenFrozenHeadersContainIdempotencyKey() {
    frozenParameters.set(parameters("POST", Map.of("idempotency-key", "business-order-42")));

    dispatch(message(Map.of()));

    assertThat(events).containsExactly("CLAIM", "EFFECT", "REPORT");
    assertThat(startBody.get()).isNull();
    assertThat(effectCount.get()).isEqualTo(1);
    assertThat(effectIdempotencyKey.get()).isEqualTo("business-order-42");
    assertThat(reportBody.get().path("success").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("即使 GET 安全请求，CLAIM 缺当前调用标识时也不能用旧消息身份执行")
  void shouldNotExecuteSafeGet_whenClaimInvocationIsMissing() {
    frozenParameters.set(parameters("GET", Map.of()));
    omitClaimInvocation.set(true);
    TaskDispatchMessage dispatchMessage = message(Map.of("partitionInvocationId", "inv-stale"));

    dispatch(dispatchMessage);

    assertThat(events).containsExactly("CLAIM");
    assertThat(startBody.get()).isNull();
    assertThat(effectCount.get()).isZero();
    assertThat(reportBody.get()).isNull();
  }

  @Test
  @DisplayName("有本地重试注解的无键 POST 已产生动作但丢响应时不重复动作")
  void shouldNotRetryUnsafePost_whenResponseIsLostAfterEffect() {
    frozenParameters.set(parameters("POST", Map.of()));
    disconnectAfterEffect.set(true);
    HttpAtomicConfig httpConfig = handlerConfig();
    HttpAtomicHandler handler = new RetryingHttpHandler(httpConfig);
    TaskDispatchMessage dispatchMessage = message(Map.of());

    dispatch(dispatchMessage, handler, null);

    assertThat(effectCount.get()).isEqualTo(1);
    assertThat(events).containsExactly("CLAIM", "START", "EFFECT", "REPORT");
    assertThat(reportBody.get().path("success").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("幂等结果已缓存时直接回报原结果，不标记开始或发送外部请求")
  void shouldNotMarkStartOrInvokePost_whenIdempotentResultIsCached() {
    frozenParameters.set(parameters("POST", Map.of()));
    SdkIdempotencyStore store = mock(SdkIdempotencyStore.class);
    SdkIdempotencyEntity existing =
        new SdkIdempotencyEntity("already committed", Map.of("receipt", "receipt-42"));
    when(store.tryAcquire("http:tx:job-1", 0L)).thenReturn(false);
    when(store.find("http:tx:job-1")).thenReturn(Optional.of(existing));
    HttpAtomicConfig httpConfig = handlerConfig();
    HttpAtomicHandler handler = new CachedHttpHandler(httpConfig);
    TaskDispatchMessage dispatchMessage = message(Map.of());

    dispatch(dispatchMessage, handler, store);

    assertThat(events).containsExactly("CLAIM", "REPORT");
    assertThat(startBody.get()).isNull();
    assertThat(effectCount.get()).isZero();
    JsonNode report = reportBody.get();
    assertThat(report.path("success").asBoolean()).isTrue();
    assertThat(report.path("message").asText()).isEqualTo("already committed");
    assertThat(report.path("outputs").path("receipt").asText()).isEqualTo("receipt-42");
  }

  @RetryOn(value = IOException.class, maxAttempts = 3, initialDelayMillis = 1)
  private static final class RetryingHttpHandler extends HttpAtomicHandler {
    private RetryingHttpHandler(HttpAtomicConfig config) {
      super(config);
    }
  }

  @Idempotent(key = "http:{tenantId}:{jobCode}")
  private static final class CachedHttpHandler extends HttpAtomicHandler {
    private CachedHttpHandler(HttpAtomicConfig config) {
      super(config);
    }
  }

  private Map<String, Object> parameters(String method, Map<String, String> headers) {
    return Map.of("url", baseUrl + "/external-effect", "method", method, "headers", headers);
  }

  private TaskDispatchMessage message(Map<String, Object> runtimeAttributes) {
    // Kafka 参数故意不同；安全分类与实际请求必须共同使用 CLAIM 返回的冻结参数。
    Map<String, Object> staleParameters = Map.of("method", "GET");
    return new TaskDispatchMessage(
        42L, "tx", "job-1", "http", "ti-9", staleParameters, runtimeAttributes);
  }

  private void dispatch(TaskDispatchMessage message) {
    HttpAtomicConfig httpConfig = handlerConfig();
    HttpAtomicHandler handler = new HttpAtomicHandler(httpConfig);
    dispatch(message, handler, null);
  }

  private HttpAtomicConfig handlerConfig() {
    return new HttpAtomicConfig("http", false, Set.of(), Set.of("GET", "POST"), 2, 1024);
  }

  private void dispatch(
      TaskDispatchMessage message, HttpAtomicHandler handler, SdkIdempotencyStore store) {
    BatchPlatformClientConfig clientConfig = BatchPlatformClientConfig.builder()
        .baseUrl(baseUrl)
        .tenantId("tx")
        .workerCode("w-1")
        .kafkaBootstrap("kafka:9092")
        .kafkaTopicPattern("batch.task.dispatch.tx.*")
        .kafkaGroupId("g")
        .httpTimeout(Duration.ofSeconds(2))
        .build();
    platform = new PlatformHttpClient(clientConfig);
    dispatcher = new TaskDispatcher(clientConfig, Map.of("http", handler), platform, store);
    dispatcher.processInWorkerThread(message);
  }

  private void handleRequest(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getPath();
      byte[] requestBody = exchange.getRequestBody().readAllBytes();
      switch (path) {
        case "/internal/tasks/42/claim" -> {
          events.add("CLAIM");
          String payload = SdkJsonMapperFactory.create().writeValueAsString(frozenParameters.get());
          Map<String, Object> claim = omitClaimInvocation.get()
              ? Map.of("payload", payload)
              : Map.of("partitionInvocationId", "inv-claim-current", "payload", payload);
          if (replaceOwnerAfterClaim.get()) {
            currentWorker.set("w-replacement");
            currentInvocation.set("inv-replacement");
          }
          sendJson(exchange, 200, claim);
        }
        case "/internal/tasks/42/non-idempotent-started" -> {
          events.add("START");
          JsonNode marker = SdkJsonMapperFactory.create().readTree(requestBody);
          startBody.set(marker);
          startMethod.set(exchange.getRequestMethod());
          boolean ownerMatches = "tx".equals(marker.path("tenantId").asText())
              && currentWorker.get().equals(marker.path("workerId").asText())
              && currentInvocation.get().equals(marker.path("partitionInvocationId").asText());
          int status = ownerMatches ? 200 : 409;
          observedStartStatus.set(status);
          exchange.sendResponseHeaders(status, -1);
        }
        case "/external-effect" -> {
          events.add("EFFECT");
          effectCount.incrementAndGet();
          effectIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
          if (!disconnectAfterEffect.get()) {
            exchange.sendResponseHeaders(200, -1);
          }
        }
        case "/internal/tasks/42/report" -> {
          events.add("REPORT");
          reportBody.set(SdkJsonMapperFactory.create().readTree(requestBody));
          exchange.sendResponseHeaders(200, -1);
        }
        default -> exchange.sendResponseHeaders(404, -1);
      }
    }
  }

  private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
    String json = SdkJsonMapperFactory.create().writeValueAsString(body);
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }
}
