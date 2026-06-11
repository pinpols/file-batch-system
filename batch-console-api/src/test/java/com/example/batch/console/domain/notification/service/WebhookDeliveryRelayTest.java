package com.example.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.console.domain.notification.entity.WebhookDeliveryLogEntity;
import com.example.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import com.example.batch.console.domain.notification.mapper.ConsoleWebhookDeliveryLogMapper;
import com.example.batch.console.domain.notification.mapper.ConsoleWebhookSubscriptionMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;

class WebhookDeliveryRelayTest {

  private ConsoleWebhookDeliveryLogMapper deliveryLogRepository;
  private ConsoleWebhookSubscriptionMapper subscriptionRepository;
  private WebhookDispatcher dispatcher;
  private SimpleMeterRegistry meterRegistry;
  private LockingTaskExecutor lockExecutor;
  private WebhookDeliveryRelay relay;

  @BeforeEach
  void setUp() throws Throwable {
    deliveryLogRepository = mock(ConsoleWebhookDeliveryLogMapper.class);
    subscriptionRepository = mock(ConsoleWebhookSubscriptionMapper.class);
    dispatcher = mock(WebhookDispatcher.class);
    meterRegistry = new SimpleMeterRegistry();
    lockExecutor = mock(LockingTaskExecutor.class);
    doAnswer(
            inv -> {
              LockingTaskExecutor.Task t = inv.getArgument(0);
              t.call();
              return null;
            })
        .when(lockExecutor)
        .executeWithLock(any(LockingTaskExecutor.Task.class), any());
    // 用户在 e1de09db 等 refactor 把 WebhookDeliveryRelay 的 @Value 配置抽到 WebhookRelayProperties,
    // 测试需要同步更新构造器调用以反映新签名
    com.example.batch.console.config.WebhookRelayProperties props =
        new com.example.batch.console.config.WebhookRelayProperties();
    relay =
        new WebhookDeliveryRelay(
            deliveryLogRepository,
            subscriptionRepository,
            dispatcher,
            lockExecutor,
            meterRegistry,
            props);
  }

  @Test
  void shouldSkipPollAfterContextClosedWithoutTakingLock() throws Throwable {
    relay.stopOnContextClosed(new ContextClosedEvent(new StaticApplicationContext()));

    relay.poll();

    verify(lockExecutor, never()).executeWithLock(any(LockingTaskExecutor.Task.class), any());
    verify(deliveryLogRepository, never()).findEligibleRetries(any(), anyInt());
  }

  @Test
  void shouldDowngradeRedisStoppingDuringShutdown() throws Throwable {
    doAnswer(
            inv -> {
              relay.stopOnContextClosed(new ContextClosedEvent(new StaticApplicationContext()));
              throw new IllegalStateException("LettuceConnectionFactory is STOPPING");
            })
        .when(lockExecutor)
        .executeWithLock(any(LockingTaskExecutor.Task.class), any());

    relay.poll();

    verify(deliveryLogRepository, never()).findEligibleRetries(any(), anyInt());
  }

  @Test
  void shouldSkipPollWhenNoEligibleRows() {
    when(deliveryLogRepository.findEligibleRetries(any(Instant.class), eq(50)))
        .thenReturn(List.of());

    relay.poll();

    verify(deliveryLogRepository, never()).claimForRetry(anyString(), anyLong());
    verify(dispatcher, never()).attemptDelivery(any(), any(), anyString());
  }

  @Test
  void shouldMarkRetrySuccessWhenDeliveryReturns200() {
    WebhookDeliveryLogEntity row = exhaustedRow(101L, 3);
    WebhookSubscriptionEntity subscription = enabledSubscription(7L);
    when(deliveryLogRepository.findEligibleRetries(any(), anyInt())).thenReturn(List.of(row));
    when(deliveryLogRepository.claimForRetry("t1", 101L)).thenReturn(1);
    when(subscriptionRepository.findByTenantAndId("t1", 7L)).thenReturn(Optional.of(subscription));
    when(dispatcher.attemptDelivery(eq(subscription), any(), eq(row.getPayloadJson())))
        .thenReturn(WebhookDeliveryResult.ok());

    relay.poll();

    // attempt 应为原 row.attempt(3) + 1 = 4
    verify(deliveryLogRepository).markRetrySuccess("t1", 101L, 4, null);
    verify(deliveryLogRepository, never())
        .markRetryFailure(anyString(), anyLong(), anyInt(), any(), any(), any());
    verify(deliveryLogRepository, never())
        .markGiveUp(anyString(), anyLong(), anyInt(), any(), anyString());
  }

  @Test
  void shouldMarkRetryFailureWithBackoffWhenBelowAbsoluteMax() {
    WebhookDeliveryLogEntity row = exhaustedRow(102L, 3); // next attempt = 4, 还没到 max 8
    WebhookSubscriptionEntity subscription = enabledSubscription(7L);
    when(deliveryLogRepository.findEligibleRetries(any(), anyInt())).thenReturn(List.of(row));
    when(deliveryLogRepository.claimForRetry("t1", 102L)).thenReturn(1);
    when(subscriptionRepository.findByTenantAndId("t1", 7L)).thenReturn(Optional.of(subscription));
    when(dispatcher.attemptDelivery(eq(subscription), any(), eq(row.getPayloadJson())))
        .thenReturn(WebhookDeliveryResult.failure(503, "service unavailable"));

    relay.poll();

    verify(deliveryLogRepository)
        .markRetryFailure(
            eq("t1"), eq(102L), eq(4), eq(503), eq("service unavailable"), any(Instant.class));
    verify(deliveryLogRepository, never())
        .markRetrySuccess(anyString(), anyLong(), anyInt(), any());
    verify(deliveryLogRepository, never())
        .markGiveUp(anyString(), anyLong(), anyInt(), any(), anyString());
    assertThat(meterRegistry.counter("batch_webhook_delivery_give_up_total").count()).isZero();
  }

  @Test
  void shouldMarkGiveUpAndIncrementCounterWhenAbsoluteMaxReached() {
    // 当前 attempt=7,下一次 nextAttempt=8,正好达到绝对最大重试次数。
    WebhookDeliveryLogEntity row = exhaustedRow(103L, 7);
    WebhookSubscriptionEntity subscription = enabledSubscription(7L);
    when(deliveryLogRepository.findEligibleRetries(any(), anyInt())).thenReturn(List.of(row));
    when(deliveryLogRepository.claimForRetry("t1", 103L)).thenReturn(1);
    when(subscriptionRepository.findByTenantAndId("t1", 7L)).thenReturn(Optional.of(subscription));
    when(dispatcher.attemptDelivery(eq(subscription), any(), eq(row.getPayloadJson())))
        .thenReturn(WebhookDeliveryResult.failure(500, "internal error"));

    relay.poll();

    verify(deliveryLogRepository).markGiveUp("t1", 103L, 8, 500, "internal error");
    assertThat(meterRegistry.counter("batch_webhook_delivery_give_up_total").count())
        .isEqualTo(1.0);
  }

  @Test
  void shouldGiveUpWhenSubscriptionDisabled() {
    WebhookDeliveryLogEntity row = exhaustedRow(104L, 3);
    WebhookSubscriptionEntity disabled = enabledSubscription(7L);
    disabled.setEnabled(false);
    when(deliveryLogRepository.findEligibleRetries(any(), anyInt())).thenReturn(List.of(row));
    when(deliveryLogRepository.claimForRetry("t1", 104L)).thenReturn(1);
    when(subscriptionRepository.findByTenantAndId("t1", 7L)).thenReturn(Optional.of(disabled));

    relay.poll();

    verify(deliveryLogRepository)
        .markGiveUp(eq("t1"), eq(104L), eq(3), any(), eq("subscription disabled or deleted"));
    verify(dispatcher, never()).attemptDelivery(any(), any(), anyString());
    assertThat(meterRegistry.counter("batch_webhook_delivery_give_up_total").count())
        .isEqualTo(1.0);
  }

  @Test
  void shouldGiveUpWhenSubscriptionMissing() {
    WebhookDeliveryLogEntity row = exhaustedRow(105L, 3);
    when(deliveryLogRepository.findEligibleRetries(any(), anyInt())).thenReturn(List.of(row));
    when(deliveryLogRepository.claimForRetry("t1", 105L)).thenReturn(1);
    when(subscriptionRepository.findByTenantAndId("t1", 7L)).thenReturn(Optional.empty());

    relay.poll();

    verify(deliveryLogRepository)
        .markGiveUp(eq("t1"), eq(105L), eq(3), any(), eq("subscription disabled or deleted"));
    verify(dispatcher, never()).attemptDelivery(any(), any(), anyString());
  }

  @Test
  void shouldSkipRowWhenClaimLost() {
    WebhookDeliveryLogEntity row = exhaustedRow(106L, 3);
    when(deliveryLogRepository.findEligibleRetries(any(), anyInt())).thenReturn(List.of(row));
    when(deliveryLogRepository.claimForRetry("t1", 106L)).thenReturn(0); // 抢占失败

    relay.poll();

    verify(subscriptionRepository, never()).findByTenantAndId(anyString(), anyLong());
    verify(dispatcher, never()).attemptDelivery(any(), any(), anyString());
    verify(deliveryLogRepository, never())
        .markRetrySuccess(anyString(), anyLong(), anyInt(), any());
  }

  @Test
  void shouldGiveUpOnPayloadDeserializationFailure() {
    WebhookDeliveryLogEntity row = exhaustedRow(107L, 3);
    row.setPayloadJson("{ this is not valid json"); // 故意坏掉
    WebhookSubscriptionEntity subscription = enabledSubscription(7L);
    when(deliveryLogRepository.findEligibleRetries(any(), anyInt())).thenReturn(List.of(row));
    when(deliveryLogRepository.claimForRetry("t1", 107L)).thenReturn(1);
    when(subscriptionRepository.findByTenantAndId("t1", 7L)).thenReturn(Optional.of(subscription));

    relay.poll();

    verify(deliveryLogRepository)
        .markGiveUp(
            eq("t1"),
            eq(107L),
            eq(3),
            any(),
            org.mockito.ArgumentMatchers.contains("payload deserialization failed"));
    verify(dispatcher, never()).attemptDelivery(any(), any(), anyString());
    assertThat(meterRegistry.counter("batch_webhook_delivery_give_up_total").count())
        .isEqualTo(1.0);
  }

  @Test
  void shouldComputeBackoffWithExponentialCap() {
    // nextAttempt 4 → 5min(基数), 5 → 10min, 6 → 20min, 7 → 30min(截断)
    assertThat(relay.computeBackoffSeconds(4)).isEqualTo(5L * 60L);
    assertThat(relay.computeBackoffSeconds(5)).isEqualTo(10L * 60L);
    assertThat(relay.computeBackoffSeconds(6)).isEqualTo(20L * 60L);
    assertThat(relay.computeBackoffSeconds(7)).isEqualTo(30L * 60L); // 40min capped to 30
    assertThat(relay.computeBackoffSeconds(8)).isEqualTo(30L * 60L);
  }

  @Test
  void shouldContinueProcessingBatchOnSingleRowError() {
    WebhookDeliveryLogEntity okRow = exhaustedRow(201L, 3);
    WebhookDeliveryLogEntity badRow = exhaustedRow(202L, 3);
    WebhookSubscriptionEntity subscription = enabledSubscription(7L);
    when(deliveryLogRepository.findEligibleRetries(any(), anyInt()))
        .thenReturn(List.of(badRow, okRow));
    when(deliveryLogRepository.claimForRetry("t1", 202L)).thenReturn(1);
    when(deliveryLogRepository.claimForRetry("t1", 201L)).thenReturn(1);
    // 202 抛异常, 201 正常成功
    when(subscriptionRepository.findByTenantAndId("t1", 7L)).thenReturn(Optional.of(subscription));
    doAnswer(
            inv -> {
              throw new RuntimeException("simulated dispatcher crash");
            })
        .when(dispatcher)
        .attemptDelivery(eq(subscription), any(), eq(badRow.getPayloadJson()));
    when(dispatcher.attemptDelivery(eq(subscription), any(), eq(okRow.getPayloadJson())))
        .thenReturn(WebhookDeliveryResult.ok());

    relay.poll();

    // 201 仍被处理
    verify(deliveryLogRepository).markRetrySuccess("t1", 201L, 4, null);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private WebhookDeliveryLogEntity exhaustedRow(long id, int attempt) {
    WebhookDeliveryLogEntity row = new WebhookDeliveryLogEntity();
    row.setId(id);
    row.setTenantId("t1");
    row.setSubscriptionId(7L);
    row.setEventType("JOB_SUCCESS");
    row.setPayloadJson(
        "{\"tenantId\":\"t1\",\"eventType\":\"JOB_SUCCESS\",\"stream\":\"jobs\","
            + "\"cursor\":\"c1\",\"emittedAt\":\"2026-04-30T10:00:00Z\",\"data\":{\"k\":\"v\"}}");
    row.setHttpStatus(500);
    row.setResponseBody("prior failure");
    row.setDeliveryStatus("EXHAUSTED");
    row.setAttempt(attempt);
    row.setNextRetryAt(Instant.parse("2026-04-30T10:05:00Z"));
    return row;
  }

  private WebhookSubscriptionEntity enabledSubscription(Long id) {
    WebhookSubscriptionEntity sub = new WebhookSubscriptionEntity();
    sub.setId(id);
    sub.setTenantId("t1");
    sub.setName("smoke");
    sub.setCallbackUrl("https://example.com/wh");
    sub.setEventTypes("*");
    sub.setSecret("s");
    sub.setEnabled(true);
    return sub;
  }
}
