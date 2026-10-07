package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookDeliveryLogMapper;
import io.github.pinpols.batch.console.support.security.SsrfGuardedDns;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
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
    WebhookEventPayload payload = new WebhookEventPayload(
        "tenant-a", "TEST", "stream-1", null, java.time.Instant.now(), java.util.Map.of());

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
    WebhookEventPayload payload = new WebhookEventPayload(
        "tenant-a", "TEST", "stream-1", null, java.time.Instant.now(), java.util.Map.of());

    WebhookDeliveryResult result = dispatcher.attemptDelivery(subscription, payload, "{}");

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).contains("restricted network range");
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
