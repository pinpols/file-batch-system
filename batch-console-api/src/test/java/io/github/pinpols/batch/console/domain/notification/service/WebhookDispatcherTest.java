package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookDeliveryLogMapper;
import io.github.pinpols.batch.console.support.security.SsrfGuardedDns;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("回调分发器: 事件类型匹配, 回调地址安全拦截与载荷签名")
class WebhookDispatcherTest {

  private ConsoleWebhookService webhookService;
  private ConsoleWebhookDeliveryLogMapper deliveryLogRepository;
  private SsrfGuardedDns ssrfGuardedDns;
  private WebhookDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    webhookService = mock(ConsoleWebhookService.class);
    deliveryLogRepository = mock(ConsoleWebhookDeliveryLogMapper.class);
    // OkHttp 客户端内置 SsrfGuardedDns 做 per-request pin;这些用例只验 dispatch 路由/签名,不真发 HTTP。
    ssrfGuardedDns = new SsrfGuardedDns(mock(BatchSecurityProperties.class));
    dispatcher = new WebhookDispatcher(webhookService, deliveryLogRepository, ssrfGuardedDns);
  }

  @Test
  @DisplayName("租户无启用订阅时异步分发直接早返回, 不写投递日志")
  void shouldNotDispatchWhenNoSubscriptions() throws InterruptedException {
    // R3-P1-11：原 Thread.sleep(200) flaky；CI 低 CPU 时异步任务可能还没运行就被 verifyNoInteractions
    // 假阳通过。改用 CountDownLatch — stub findEnabledSubscriptions 在被调用时 countDown，
    // 主线程 await 后才执行 verify，保证 async 任务确实进入了 dispatchOne 入口（且因空列表早 return）。
    CountDownLatch findCalled = new CountDownLatch(1);
    when(webhookService.findEnabledSubscriptions("tenant-a")).thenAnswer(inv -> {
      findCalled.countDown();
      return Collections.emptyList();
    });

    dispatcher.dispatchAsync("tenant-a", "JOB_SUCCESS", "stream-1", "cursor-1", "data", null);

    assertThat(findCalled.await(2, TimeUnit.SECONDS)).isTrue();
    // findEnabledSubscriptions 已返回空列表 → 同一 task 内立刻早返回，
    // 后续 deliveryLogRepository 调用必然不会发生
    verifyNoInteractions(deliveryLogRepository);
  }

  @Test
  @DisplayName("订阅事件类型为通配符时, 任意事件都匹配")
  void shouldMatchWildcardEventType() throws Exception {
    Method matches =
        WebhookDispatcher.class.getDeclaredMethod("matches", String.class, String.class);
    matches.setAccessible(true);

    boolean result = (boolean) matches.invoke(dispatcher, "*", "JOB_SUCCESS");

    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("订阅事件类型清单包含该事件时, 判定为匹配")
  void shouldMatchSpecificEventType() throws Exception {
    Method matches =
        WebhookDispatcher.class.getDeclaredMethod("matches", String.class, String.class);
    matches.setAccessible(true);

    boolean result = (boolean) matches.invoke(dispatcher, "JOB_SUCCESS,JOB_FAILED", "JOB_SUCCESS");

    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("订阅事件类型清单不含该事件时, 判定为不匹配")
  void shouldNotMatchUnrelatedEventType() throws Exception {
    Method matches =
        WebhookDispatcher.class.getDeclaredMethod("matches", String.class, String.class);
    matches.setAccessible(true);

    boolean result = (boolean) matches.invoke(dispatcher, "JOB_SUCCESS", "WORKFLOW_FAILED");

    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("事件类型统一转为大写后, 再参与匹配")
  void shouldNormalizeEventTypeToUpperCase() throws Exception {
    Method normalizeEventType =
        WebhookDispatcher.class.getDeclaredMethod("normalizeEventType", String.class);
    normalizeEventType.setAccessible(true);

    String result = (String) normalizeEventType.invoke(dispatcher, "job_success");

    assertThat(result).isEqualTo("JOB_SUCCESS");
  }

  @Test
  @DisplayName("事件类型为空时, 归一为未知类型")
  void shouldNormalizeNullEventTypeToUnknown() throws Exception {
    Method normalizeEventType =
        WebhookDispatcher.class.getDeclaredMethod("normalizeEventType", String.class);
    normalizeEventType.setAccessible(true);

    String result = (String) normalizeEventType.invoke(dispatcher, (String) null);

    assertThat(result).isEqualTo("UNKNOWN");
  }

  @Test
  @DisplayName("回调地址域名解析到内网回环时投递被拦, 失败原因指向受限网段")
  void shouldBlockDeliveryWhenCallbackHostResolvesToInternalAddress() {
    // 真 per-request pin 的端到端证明:回调用**主机名**(rebinding 的实际攻击形态),它解析到内网回环,
    // OkHttp 建连前经 SsrfGuardedDns 校验被拦,投递折叠为 failure。若 guard 是装饰性的(未接进真实 client),
    // 这里会静默连到内网。注:字面量内网 IP 由 OkHttp 短路不走 Dns,但那类在建订阅时已被 CallbackUrlValidator 拦。
    WebhookSubscriptionEntity subscription = new WebhookSubscriptionEntity();
    subscription.setCallbackUrl("https://localhost:9/hook");
    WebhookEventPayload payload =
        new WebhookEventPayload("tenant-a", "TEST", "stream-1", null, Instant.now(), Map.of());

    WebhookDeliveryResult result = dispatcher.attemptDelivery(subscription, payload, "{}");

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).contains("restricted network range");
  }

  @Test
  @DisplayName("回调地址为字面量元数据内网地址时投递被拦, 失败原因指向受限网段")
  void shouldBlockDeliveryWhenCallbackIsLiteralInternalIp() {
    // Critical 回归防护:OkHttp 对字面量 IP 短路不走 SsrfGuardedDns,故 deliver 里补 guard 兜底拦字面量内网/元数据 IP。
    // 若没有这道兜底,租户建 WEBHOOK 渠道 url=169.254.169.254 点测试即打云 metadata。
    WebhookSubscriptionEntity subscription = new WebhookSubscriptionEntity();
    subscription.setCallbackUrl("https://169.254.169.254/latest/meta-data/");
    WebhookEventPayload payload =
        new WebhookEventPayload("tenant-a", "TEST", "stream-1", null, Instant.now(), Map.of());

    WebhookDeliveryResult result = dispatcher.attemptDelivery(subscription, payload, "{}");

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).contains("restricted network range");
  }

  @Test
  @DisplayName("Webhook 非成功响应正文中的凭据不进入失败摘要")
  void shouldMaskCredentialsInWebhookErrorResponse() throws Exception {
    byte[] errorBody =
        "password=probe-secret; ｔｏｋｅｎ=unicode-secret; retry later".getBytes(StandardCharsets.UTF_8);
    var endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    endpoint.createContext("/error", exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(503, errorBody.length);
      try (var body = exchange.getResponseBody()) {
        body.write(errorBody);
      }
    });
    String original = System.getProperty("batch.security.ssrf-guard.allow-private");
    System.setProperty("batch.security.ssrf-guard.allow-private", "true");
    endpoint.start();
    try {
      var subscription = new WebhookSubscriptionEntity();
      subscription.setCallbackUrl("http://127.0.0.1:" + endpoint.getAddress().getPort() + "/error");
      var payload = new WebhookEventPayload(
          "tenant-a", "JOB_FAILED", "job-instances", null, Instant.now(), Map.of());

      WebhookDeliveryResult result = dispatcher.attemptDelivery(subscription, payload, "{}");

      assertThat(result.success()).isFalse();
      assertThat(result.httpStatus()).isEqualTo(503);
      assertThat(result.errorSummary()).contains("password=****", "token=****", "retry later");
      assertThat(result.errorSummary()).doesNotContain("probe-secret", "unicode-secret");
    } finally {
      endpoint.stop(0);
      if (original == null) System.clearProperty("batch.security.ssrf-guard.allow-private");
      else System.setProperty("batch.security.ssrf-guard.allow-private", original);
    }
  }

  @Test
  @DisplayName("Job/Workflow 终态和告警通知传稳定幂等键，普通变更游标不冒用")
  void shouldApplyStableIdempotencyToTerminalAndAlertmanagerEvents() throws Exception {
    var received = new LinkedBlockingQueue<Optional<String>>();
    var endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    endpoint.createContext("/headers", exchange -> {
      received.add(Optional.ofNullable(exchange.getRequestHeaders().getFirst("Idempotency-Key")));
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    String original = System.getProperty("batch.security.ssrf-guard.allow-private");
    System.setProperty("batch.security.ssrf-guard.allow-private", "true");
    endpoint.start();
    try {
      var subscription = new WebhookSubscriptionEntity();
      subscription.setCallbackUrl(
          "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/headers");
      for (String type : List.of(
          "JOB_SUCCESS",
          "JOB_FAILED",
          "WORKFLOW_SUCCESS",
          "WORKFLOW_FAILED",
          "JOB_CHANGED",
          "WORKFLOW_CHANGED")) {
        String stream = type.startsWith("JOB_") ? "job-instances" : "workflow-runs";
        String cursor = "ta:" + type;
        var payload = new WebhookEventPayload("ta", type, stream, cursor, Instant.now(), Map.of());
        assertThat(dispatcher.attemptDelivery(subscription, payload, "{}").success())
            .isTrue();
        var key = received.poll(5, TimeUnit.SECONDS);
        assertThat(key).isNotNull();
        if (type.endsWith("CHANGED")) {
          assertThat(key).isEmpty();
        } else {
          assertThat(key).isPresent();
          assertThat(new String(
                  Base64.getUrlDecoder().decode(key.orElseThrow()), StandardCharsets.UTF_8))
              .isEqualTo(cursor);
        }
      }
      String alertmanagerCursor = "ta:alertmanager:entity:firing";
      WebhookEventPayload alertmanagerPayload = new WebhookEventPayload(
          "ta", "ALERTMANAGER", "alertmanager", alertmanagerCursor, Instant.now(), Map.of());
      WebhookDeliveryResult alertmanagerResult =
          dispatcher.attemptDelivery(subscription, alertmanagerPayload, "{}");
      assertThat(alertmanagerResult.success()).isTrue();
      Optional<String> alertmanagerKey = received.poll(5, TimeUnit.SECONDS);
      assertThat(alertmanagerKey).isPresent();
      String decodedAlertmanagerKey = new String(
          Base64.getUrlDecoder().decode(alertmanagerKey.orElseThrow()), StandardCharsets.UTF_8);
      assertThat(decodedAlertmanagerKey).isEqualTo(alertmanagerCursor);
    } finally {
      endpoint.stop(0);
      if (original == null) System.clearProperty("batch.security.ssrf-guard.allow-private");
      else System.setProperty("batch.security.ssrf-guard.allow-private", original);
    }
  }

  @Test
  @DisplayName("载荷签名带算法前缀, 摘要为十六进制定长")
  void shouldSignPayloadWithHmacSha256() throws Exception {
    Method sign = WebhookDispatcher.class.getDeclaredMethod("sign", String.class, String.class);
    sign.setAccessible(true);

    String result = (String) sign.invoke(dispatcher, "{\"event\":\"test\"}", "my-secret");

    assertThat(result).startsWith("sha256=");
    assertThat(result).hasSize("sha256=".length() + 64); // sha256 hex = 64 chars
  }
}
