package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.support.ratelimit.SlidingWindowRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class SubscriptionRuleWebhookDispatcherTest {

  @Mock private SubscriptionRuleMapper subscriptionRuleMapper;
  @Mock private WebhookDispatcher webhookDispatcher;
  @Mock private NotificationSenderRegistry senderRegistry;
  @Mock private SlidingWindowRateLimiter sendRateLimiter;
  @Mock private NotificationDeliveryLogMapper deliveryLogMapper;
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private SubscriptionRuleWebhookDispatcher dispatcher;

  private SubscriptionRuleWebhookDispatcher newDispatcher() {
    // 限流默认放行;"超限丢弃"用例单独覆盖。
    lenient().when(sendRateLimiter.tryAcquire(any(), anyInt())).thenReturn(true);
    dispatcher =
        new SubscriptionRuleWebhookDispatcher(
            subscriptionRuleMapper,
            webhookDispatcher,
            senderRegistry,
            sendRateLimiter,
            meterRegistry,
            deliveryLogMapper);
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
  void shouldFailOpenAndRecordCounter_whenDedupRateLimiterThrows() {
    // arrange: dedup 键(notify:dedup:*)查询 Redis 时抛 DataAccessException,其余键正常放行。
    // fail-open 语义:不误判为重复,继续投递;同时须留痕(log.warn + counter),而非静默吞掉。
    Map<String, Object> rule =
        Map.of(
            "tenant_id", "tenant-a",
            "channel_code", "ops-hook",
            "channel_type", "WEBHOOK",
            "event_types", "JOB_SUCCESS",
            "config_json", "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenReturn(List.of(rule));
    when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());
    when(sendRateLimiter.tryAcquire(any(), anyInt())).thenReturn(true);
    when(sendRateLimiter.tryAcquire(
            argThat(key -> key != null && key.startsWith("notify:dedup:")), anyInt()))
        .thenThrow(new DataAccessResourceFailureException("redis down"));

    dispatcher =
        new SubscriptionRuleWebhookDispatcher(
            subscriptionRuleMapper,
            webhookDispatcher,
            senderRegistry,
            sendRateLimiter,
            meterRegistry,
            deliveryLogMapper);
    dispatcher.dispatch("tenant-a", "JOB_SUCCESS", "stream-1", "cursor-1", "data", Instant.now());

    verify(webhookDispatcher, timeout(2000)).attemptDelivery(any(), any(), any());
    assertThat(meterRegistry.find("notification.dedup.redis_fallback").counter()).isNotNull();
    assertThat(meterRegistry.find("notification.dedup.redis_fallback").counter().count())
        .isEqualTo(1.0);
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
  void shouldTenantScopeRateLimitKeys_whenSameChannelCodeAcrossTenants() {
    // 两租户可合法配置同名 channelCode(唯一约束是 (tenant_id, channel_code))。
    // 限流/去重/目标 key 必须带 tenant 前缀,否则 A 打满后 B 同名渠道合法告警被静默压制(跨租串扰)。
    Map<String, Object> ruleA =
        Map.of(
            "tenant_id", "tenant-a",
            "channel_code", "ops-hook",
            "channel_type", "WEBHOOK",
            "event_types", "JOB_SUCCESS",
            "config_json", "{\"url\":\"https://hook.example.com/in\"}");
    Map<String, Object> ruleB =
        Map.of(
            "tenant_id", "tenant-b",
            "channel_code", "ops-hook",
            "channel_type", "WEBHOOK",
            "event_types", "JOB_SUCCESS",
            "config_json", "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenReturn(List.of(ruleA));
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-b", "JOB_SUCCESS"))
        .thenReturn(List.of(ruleB));
    lenient()
        .when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());

    newDispatcher().dispatch("tenant-a", "JOB_SUCCESS", "s", "c", "data", Instant.now());
    dispatcher.dispatch("tenant-b", "JOB_SUCCESS", "s", "c", "data", Instant.now());

    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(sendRateLimiter, timeout(2000).atLeastOnce()).tryAcquire(keys.capture(), anyInt());
    List<String> sendKeys =
        keys.getAllValues().stream().filter(k -> k.startsWith("notify:send:")).toList();
    // 两租户的 send 限流 key 都带各自 tenant 前缀,且彼此不同 → 不共享限流窗口。
    assertThat(sendKeys).contains("notify:send:tenant-a:ops-hook", "notify:send:tenant-b:ops-hook");
    assertThat(keys.getAllValues())
        .anyMatch(k -> k.startsWith("notify:dedup:tenant-a:"))
        .anyMatch(k -> k.startsWith("notify:dedup:tenant-b:"))
        .anyMatch(k -> k.startsWith("notify:dest:tenant-a:"))
        .anyMatch(k -> k.startsWith("notify:dest:tenant-b:"));
  }

  @Test
  void shouldIncrementDropCounter_whenChannelSendRateLimitExceeded() {
    Map<String, Object> rule =
        Map.of(
            "tenant_id", "tenant-a",
            "channel_code", "ops-hook",
            "channel_type", "WEBHOOK",
            "event_types", "JOB_SUCCESS",
            "config_json", "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenReturn(List.of(rule));
    when(sendRateLimiter.tryAcquire(any(), anyInt())).thenReturn(false);
    dispatcher =
        new SubscriptionRuleWebhookDispatcher(
            subscriptionRuleMapper,
            webhookDispatcher,
            senderRegistry,
            sendRateLimiter,
            meterRegistry,
            deliveryLogMapper);

    dispatcher.dispatch("tenant-a", "JOB_SUCCESS", "s", "c", "data", Instant.now());

    assertThat(
            meterRegistry
                .find("notification.dropped")
                .tag("reason", "channel_ratelimit")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void shouldIncrementDropCounter_whenNoSenderForChannelType() throws InterruptedException {
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
    // senderRegistry.resolve("EMAIL") 默认返回 null → no_sender 丢弃路径。

    newDispatcher().dispatch("tenant-a", "JOB_SUCCESS", "s", "c", "data", Instant.now());

    assertThat(queried.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(
            meterRegistry.find("notification.dropped").tag("reason", "no_sender").counter().count())
        .isEqualTo(1.0);
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
            subscriptionRuleMapper,
            webhookDispatcher,
            senderRegistry,
            sendRateLimiter,
            meterRegistry,
            deliveryLogMapper);

    dispatcher.dispatch("tenant-a", "JOB_SUCCESS", "s", "c", "data", Instant.now());

    verify(webhookDispatcher, never()).attemptDelivery(any(), any(), any());
  }

  @Test
  void shouldMatchAndDispatch_escalationEventAlignedWithCatalog() {
    // Bug4:AlertEscalationNotifier 发 ALERT_ESCALATED(与事件目录一致),前端可配 subscription_rule
    // event_types=ALERT_ESCALATED 来订阅升级告警;此处验证该事件类型能匹配到规则并投递。
    Map<String, Object> rule =
        Map.of(
            "id", 1L,
            "tenant_id", "tenant-a",
            "channel_code", "ops-hook",
            "channel_type", "WEBHOOK",
            "event_types", "ALERT_ESCALATED",
            "config_json", "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "ALERT_ESCALATED"))
        .thenReturn(List.of(rule));
    when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());

    newDispatcher()
        .dispatch(
            "tenant-a",
            "ALERT_ESCALATED",
            "alerts",
            "cursor-1",
            Map.of("alertId", 55, "severity", "HIGH"),
            Instant.now());

    verify(webhookDispatcher, timeout(2000)).attemptDelivery(any(), any(), any());
  }

  @Test
  void shouldPersistSuccessDeliveryLog_whenWebhookDelivered() {
    Map<String, Object> rule =
        Map.of(
            "id", 42L,
            "tenant_id", "tenant-a",
            "channel_code", "ops-hook",
            "channel_type", "WEBHOOK",
            "event_types", "JOB_SUCCESS",
            "config_json", "{\"url\":\"https://hook.example.com/in\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_SUCCESS"))
        .thenReturn(List.of(rule));
    when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());

    newDispatcher()
        .dispatch(
            "tenant-a", "JOB_SUCCESS", "stream-1", "cursor-1", Map.of("alertId", 7), Instant.now());

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.captor();
    verify(deliveryLogMapper, timeout(2000)).insert(captor.capture());
    assertThat(captor.getValue())
        .containsEntry("tenantId", "tenant-a")
        .containsEntry("ruleId", 42L)
        .containsEntry("channelCode", "ops-hook")
        .containsEntry("eventType", "JOB_SUCCESS")
        .containsEntry("alertEventId", 7L)
        .containsEntry("deliveryStatus", "SUCCESS");
    assertThat(captor.getValue().get("errorMessage")).isNull();
  }

  @Test
  void shouldPersistFailedDeliveryLog_whenSenderExhausts() {
    Map<String, Object> rule =
        Map.of(
            "id", 9L,
            "tenant_id", "tenant-a",
            "channel_code", "ops-wecom",
            "channel_type", "WECOM",
            "event_types", "JOB_FAILED",
            "config_json", "{\"url\":\"https://qyapi.weixin.qq.com/robot?key=x\"}");
    when(subscriptionRuleMapper.selectEnabledByEventType("tenant-a", "JOB_FAILED"))
        .thenReturn(List.of(rule));
    NotificationSender sender = org.mockito.Mockito.mock(NotificationSender.class);
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any())).thenReturn(WebhookDeliveryResult.failure(200, "wecom errcode=93000"));

    newDispatcher()
        .dispatch("tenant-a", "JOB_FAILED", "stream-1", "cursor-1", Map.of(), Instant.now());

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.captor();
    verify(deliveryLogMapper, timeout(2000)).insert(captor.capture());
    assertThat(captor.getValue())
        .containsEntry("tenantId", "tenant-a")
        .containsEntry("ruleId", 9L)
        .containsEntry("channelCode", "ops-wecom")
        .containsEntry("eventType", "JOB_FAILED")
        .containsEntry("deliveryStatus", "FAILED")
        .containsEntry("errorMessage", "wecom errcode=93000");
  }
}
