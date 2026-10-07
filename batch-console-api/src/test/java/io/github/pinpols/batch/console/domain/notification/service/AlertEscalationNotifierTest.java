package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.config.AlertEscalationNotifyProperties;
import io.github.pinpols.batch.console.domain.notification.entity.AlertEscalationNotificationOutboxEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEscalationNotificationOutboxMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEventMapper;
import io.github.pinpols.batch.console.domain.notification.service.AlertEscalationNotifier.AlertEscalationNotifyPayload;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;

@DisplayName("告警升级通知轮询器: 水位过滤, 待发补投与单行失败隔离")
class AlertEscalationNotifierTest {

  private AlertEventMapper alertEventMapper;
  private AlertEscalationNotificationOutboxMapper outboxMapper;
  private AlertEscalationNotificationOutboxService outboxService;
  private ConsoleRealtimeEventPort domainEventPublisher;
  private LockingTaskExecutor lockExecutor;
  private SimpleMeterRegistry meterRegistry;
  private AlertEscalationNotifier notifier;

  @BeforeEach
  void setUp() throws Throwable {
    alertEventMapper = mock(AlertEventMapper.class);
    outboxMapper = mock(AlertEscalationNotificationOutboxMapper.class);
    outboxService = mock(AlertEscalationNotificationOutboxService.class);
    domainEventPublisher = mock(ConsoleRealtimeEventPort.class);
    lockExecutor = mock(LockingTaskExecutor.class);
    meterRegistry = new SimpleMeterRegistry();
    doAnswer(inv -> {
          Runnable t = inv.getArgument(0);
          t.run();
          return null;
        })
        .when(lockExecutor)
        .executeWithLock(any(Runnable.class), any());
    when(outboxMapper.selectPending(any(), anyInt(), any(), any())).thenReturn(List.of());
    notifier = new AlertEscalationNotifier(
        alertEventMapper,
        outboxMapper,
        outboxService,
        domainEventPublisher,
        lockExecutor,
        new AlertEscalationNotifyProperties(),
        meterRegistry);
  }

  private static AlertEventEntity escalated(long id, String tenantId, int tier, int notifiedTier) {
    AlertEventEntity e = new AlertEventEntity();
    e.setId(id);
    e.setTenantId(tenantId);
    e.setAlertType("SLA_BREACH");
    e.setSeverity("CRITICAL");
    e.setTitle("job stuck past SLA");
    e.setStatus("OPEN");
    e.setEscalationTier(tier);
    e.setEscalationNotifiedTier(notifiedTier);
    e.setTraceId("trace-" + id);
    return e;
  }

  private static AlertEscalationNotificationOutboxEntity outbox(
      long id, String tenantId, int tier) {
    AlertEscalationNotifyPayload payload = new AlertEscalationNotifyPayload(
        100L + id, "SLA_BREACH", "CRITICAL", "job stuck past SLA", tier, "trace-" + id);
    AlertEscalationNotificationOutboxEntity row = new AlertEscalationNotificationOutboxEntity();
    row.setId(id);
    row.setTenantId(tenantId);
    row.setAlertEventId(payload.alertId());
    row.setEscalationTier(tier);
    row.setStream("alerts");
    row.setEventType("ALERT_ESCALATED");
    row.setPayloadJson(JsonUtils.toJson(payload));
    row.setAttemptCount(0);
    return row;
  }

  @Test
  @DisplayName("没有待升级的告警事件时, 既不投递实时事件也不写入待发记录")
  void shouldSkipPollWhenNoEligibleRows() {
    when(alertEventMapper.selectEscalatedPendingNotify(anyInt())).thenReturn(List.of());

    notifier.poll();

    verify(domainEventPublisher, never()).publishChanged(any(), any(), any(), any());
    verify(outboxService, never()).enqueue(any(), any(), any(), any());
  }

  @Test
  @DisplayName("升级事件入队成功后同一轮把待发记录投递出去, 并累加通知计数")
  void shouldEnqueueEscalatedEventAndPublishPendingOutbox() {
    when(alertEventMapper.selectEscalatedPendingNotify(anyInt()))
        .thenReturn(List.of(escalated(11L, "t1", 2, 1)));
    AlertEscalationNotificationOutboxEntity row = outbox(31L, "t1", 2);
    when(outboxMapper.selectPending(any(), anyInt(), any(), any())).thenReturn(List.of(row));
    when(outboxMapper.markPublishing(eq(31L), eq("t1"), any(), any(), any())).thenReturn(1);
    when(outboxMapper.markPublished(eq(31L), eq("t1"), any(), any())).thenReturn(1);

    notifier.poll();

    verify(outboxService)
        .enqueue(
            eq(escalated(11L, "t1", 2, 1)),
            eq("alerts"),
            eq("ALERT_ESCALATED"),
            any(AlertEscalationNotifyPayload.class));
    ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
    verify(domainEventPublisher)
        .publishChanged(eq("t1"), eq("alerts"), eq("ALERT_ESCALATED"), payloadCaptor.capture());
    assertThat(payloadCaptor.getValue()).isInstanceOf(AlertEscalationNotifyPayload.class);
    AlertEscalationNotifyPayload payload = (AlertEscalationNotifyPayload) payloadCaptor.getValue();
    assertThat(payload.alertId()).isEqualTo(131L);
    assertThat(payload.escalationTier()).isEqualTo(2);
    assertThat(payload.severity()).isEqualTo("CRITICAL");
    assertThat(payload.alertType()).isEqualTo("SLA_BREACH");

    verify(outboxMapper).markPublished(eq(31L), eq("t1"), any(), any());
    assertThat(meterRegistry.counter("batch.alert.escalation.notifications").count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("升级事件入队未取得所有权时不投递, 通知计数保持零")
  void shouldNotPublishWhenOnlyEnqueueLosesOwnership() {
    when(alertEventMapper.selectEscalatedPendingNotify(anyInt()))
        .thenReturn(List.of(escalated(12L, "t1", 1, 0)));

    notifier.poll();

    verify(outboxService)
        .enqueue(any(AlertEventEntity.class), eq("alerts"), eq("ALERT_ESCALATED"), any());
    verify(domainEventPublisher, never()).publishChanged(any(), any(), any(), any());
    assertThat(meterRegistry.counter("batch.alert.escalation.notifications").count())
        .isEqualTo(0.0);
  }

  @Test
  @DisplayName("事件当前层级不高于已通知层级时跳过, 既不下发也不入队")
  void shouldSkipRowAlreadyNotifiedAtCurrentTier() {
    // 防御:即便 select 漏过滤,tier <= notifiedTier 也不发。
    when(alertEventMapper.selectEscalatedPendingNotify(anyInt()))
        .thenReturn(List.of(escalated(13L, "t1", 2, 2)));

    notifier.poll();

    verify(domainEventPublisher, never()).publishChanged(any(), any(), any(), any());
    verify(outboxService, never()).enqueue(any(), any(), any(), any());
  }

  @Test
  @DisplayName("单条事件入队抛异常时只跳过该条, 同批其余租户继续投递")
  void shouldContinueBatchWhenOneRowThrows() {
    when(alertEventMapper.selectEscalatedPendingNotify(anyInt()))
        .thenReturn(List.of(escalated(14L, "t1", 1, 0), escalated(15L, "t2", 1, 0)));
    doThrow(new RuntimeException("enqueue boom"))
        .when(outboxService)
        .enqueue(eq(escalated(14L, "t1", 1, 0)), any(), any(), any());
    AlertEscalationNotificationOutboxEntity row = outbox(41L, "t2", 1);
    when(outboxMapper.selectPending(any(), anyInt(), any(), any())).thenReturn(List.of(row));
    when(outboxMapper.markPublishing(eq(41L), eq("t2"), any(), any(), any())).thenReturn(1);
    when(outboxMapper.markPublished(eq(41L), eq("t2"), any(), any())).thenReturn(1);

    notifier.poll();

    verify(outboxService)
        .enqueue(eq(escalated(15L, "t2", 1, 0)), eq("alerts"), eq("ALERT_ESCALATED"), any());
    verify(domainEventPublisher)
        .publishChanged(eq("t2"), eq("alerts"), eq("ALERT_ESCALATED"), any());
    verify(outboxMapper).markPublished(eq(41L), eq("t2"), any(), any());
  }

  @Test
  @DisplayName("待发记录投递抛异常时标记失败, 不记为已发布")
  void shouldMarkOutboxFailedWhenPublishThrows() {
    when(alertEventMapper.selectEscalatedPendingNotify(anyInt())).thenReturn(List.of());
    AlertEscalationNotificationOutboxEntity row = outbox(51L, "t1", 1);
    row.setAttemptCount(0);
    when(outboxMapper.selectPending(any(), anyInt(), any(), any())).thenReturn(List.of(row));
    when(outboxMapper.markPublishing(eq(51L), eq("t1"), any(), any(), any())).thenReturn(1);
    doThrow(new RuntimeException("publish boom"))
        .when(domainEventPublisher)
        .publishChanged(eq("t1"), eq("alerts"), eq("ALERT_ESCALATED"), any());

    notifier.poll();

    verify(outboxMapper).markFailed(eq(51L), eq("t1"), any(), any(), eq("publish boom"), any());
    verify(outboxMapper, never()).markPublished(eq(51L), eq("t1"), any(), any());
  }

  @Test
  @DisplayName("上下文关闭后不再取锁, 也不查询待升级事件")
  void shouldSkipPollAfterContextClosedWithoutTakingLock() throws Throwable {
    notifier.stopOnContextClosed(new ContextClosedEvent(new StaticApplicationContext()));

    notifier.poll();

    verify(lockExecutor, never()).executeWithLock(any(Runnable.class), any());
    verify(alertEventMapper, never()).selectEscalatedPendingNotify(anyInt());
  }

  @Test
  @DisplayName("多租户多条记录各自投递一次, 并逐条标记已发布")
  void shouldPublishOncePerRowAcrossTenants() {
    when(alertEventMapper.selectEscalatedPendingNotify(anyInt()))
        .thenReturn(List.of(escalated(21L, "t1", 1, 0), escalated(22L, "t2", 3, 2)));
    when(outboxMapper.selectPending(any(), anyInt(), any(), any()))
        .thenReturn(List.of(outbox(61L, "t1", 1), outbox(62L, "t2", 3)));
    when(outboxMapper.markPublishing(any(), any(), any(), any(), any())).thenReturn(1);
    when(outboxMapper.markPublished(any(), any(), any(), any())).thenReturn(1);

    notifier.poll();

    verify(outboxService, times(2))
        .enqueue(any(AlertEventEntity.class), eq("alerts"), eq("ALERT_ESCALATED"), any());
    verify(domainEventPublisher, times(2))
        .publishChanged(any(), eq("alerts"), eq("ALERT_ESCALATED"), any());
    verify(outboxMapper, times(2)).markPublished(any(), any(), any(), any());
  }
}
