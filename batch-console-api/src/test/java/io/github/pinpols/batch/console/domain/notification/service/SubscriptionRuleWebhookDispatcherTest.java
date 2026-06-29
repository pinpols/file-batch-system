package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.support.ratelimit.SlidingWindowRateLimiter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubscriptionRuleWebhookDispatcherTest {

  @Mock private SubscriptionRuleMapper subscriptionRuleMapper;
  @Mock private WebhookDispatcher webhookDispatcher;
  @Mock private NotificationSenderRegistry senderRegistry;
  @Mock private SlidingWindowRateLimiter sendRateLimiter;

  private SubscriptionRuleWebhookDispatcher dispatcher;

  private SubscriptionRuleWebhookDispatcher newDispatcher() {
    // 限流默认放行;"超限丢弃"用例单独覆盖。
    lenient().when(sendRateLimiter.tryAcquire(any(), anyInt())).thenReturn(true);
    dispatcher =
        new SubscriptionRuleWebhookDispatcher(
            subscriptionRuleMapper, webhookDispatcher, senderRegistry, sendRateLimiter);
    return dispatcher;
  }

  @AfterEach
  void tearDown() {
    if (dispatcher != null) {
      dispatcher.shutdown();
    }
  }

  @Test
  void shouldGeneratePendingDelivery_whenWebhookChannelRuleMatches() {
    // arrange: 一条 WEBHOOK 类型规则,config_json 带 url + secret
    Map<String, Object> rule =
        Map.of(
            "tenant_id", "tenant-a",
            "channel_code", "ops-hook",
            "channel_type", "WEBHOOK",
            "event_types", "JOB_SUCCESS,JOB_FAILED",
            "config_json", "{\"url\":\"https://hook.example.com/in\",\"secret\":\"s3cr3t\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenReturn(List.of(rule));
    when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());

    // act
    newDispatcher()
        .dispatch("tenant-a", "JOB_SUCCESS", "stream-1", "cursor-1", "data", Instant.now());

    // assert: 异步投递最终调用 attemptDelivery,且喂入的合成订阅带 config 的 url/secret
    ArgumentCaptor<WebhookSubscriptionEntity> captor =
        ArgumentCaptor.forClass(WebhookSubscriptionEntity.class);
    verify(webhookDispatcher, timeout(2000)).attemptDelivery(captor.capture(), any(), any());
    WebhookSubscriptionEntity synthetic = captor.getValue();
    assertThat(synthetic.getCallbackUrl()).isEqualTo("https://hook.example.com/in");
    assertThat(synthetic.getSecret()).isEqualTo("s3cr3t");
  }

  @Test
  void shouldSkipAndLog_whenChannelTypeNotWebhook() throws InterruptedException {
    // arrange: EMAIL 渠道规则 —— 本轮不投递,必须 log 跳过而非静默丢弃
    CountDownLatch queried = new CountDownLatch(1);
    Map<String, Object> rule =
        Map.of(
            "tenant_id", "tenant-a",
            "channel_code", "ops-mail",
            "channel_type", "EMAIL",
            "event_types", "JOB_SUCCESS",
            "config_json", "{\"to\":\"ops@example.com\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenAnswer(
            inv -> {
              queried.countDown();
              return List.of(rule);
            });

    // act
    newDispatcher().dispatch("tenant-a", "JOB_SUCCESS", "stream-1", "cursor-1", "data", null);

    // assert: 查询发生了(规则被处理过),但 EMAIL 渠道绝不调用 webhook 投递(被 log.warn 跳过)
    assertThat(queried.await(2, TimeUnit.SECONDS)).isTrue();
    verify(webhookDispatcher, never()).attemptDelivery(any(), any(), any());
  }

  @Test
  void shouldNotMatch_whenSeverityFilterMissesPayload() {
    // arrange: 规则要求 severity=CRITICAL,但事件 payload 是 WARN → 不命中,不投递
    Map<String, Object> rule =
        Map.of(
            "tenant_id", "tenant-a",
            "channel_code", "ops-hook",
            "channel_type", "WEBHOOK",
            "event_types", "JOB_FAILED",
            "severity_filter", "CRITICAL",
            "config_json", "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_FAILED"))
        .thenReturn(List.of(rule));

    // act
    newDispatcher()
        .dispatch(
            "tenant-a",
            "JOB_FAILED",
            "stream-1",
            "cursor-1",
            Map.of("severity", "WARN"),
            Instant.now());

    // assert
    verify(webhookDispatcher, never()).attemptDelivery(any(), any(), any());
  }

  @Test
  void shouldDeliver_whenSeverityFilterMatchesPayload() {
    // arrange: severity=CRITICAL 命中
    Map<String, Object> rule =
        Map.of(
            "tenant_id", "tenant-a",
            "channel_code", "ops-hook",
            "channel_type", "WEBHOOK",
            "event_types", "JOB_FAILED",
            "severity_filter", "CRITICAL,FATAL",
            "config_json", "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_FAILED"))
        .thenReturn(List.of(rule));
    when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());

    // act
    newDispatcher()
        .dispatch(
            "tenant-a",
            "JOB_FAILED",
            "stream-1",
            "cursor-1",
            Map.of("severity", "critical"),
            Instant.now());

    // assert
    verify(webhookDispatcher, timeout(2000)).attemptDelivery(any(), any(), any());
  }

  @Test
  void shouldDropDelivery_whenChannelSendRateLimitExceeded() {
    Map<String, Object> rule =
        Map.of(
            "tenant_id", "tenant-a",
            "channel_code", "ops-hook",
            "channel_type", "WEBHOOK",
            "event_types", "JOB_SUCCESS",
            "config_json", "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenReturn(List.of(rule));
    // 限流器拒绝 → 直接丢弃,不触达投递。
    when(sendRateLimiter.tryAcquire(any(), anyInt())).thenReturn(false);
    dispatcher =
        new SubscriptionRuleWebhookDispatcher(
            subscriptionRuleMapper, webhookDispatcher, senderRegistry, sendRateLimiter);

    dispatcher.dispatch("tenant-a", "JOB_SUCCESS", "s", "c", "data", Instant.now());

    verify(webhookDispatcher, never()).attemptDelivery(any(), any(), any());
  }
}
