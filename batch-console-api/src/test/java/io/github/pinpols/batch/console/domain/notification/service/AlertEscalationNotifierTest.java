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
import io.github.pinpols.batch.console.config.AlertEscalationNotifyProperties;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEventMapper;
import io.github.pinpols.batch.console.domain.notification.service.AlertEscalationNotifier.AlertEscalationNotifyPayload;
import io.github.pinpols.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;

class AlertEscalationNotifierTest {

  private AlertEventMapper alertEventMapper;
  private ConsoleRealtimeDomainEventPublisher domainEventPublisher;
  private LockingTaskExecutor lockExecutor;
  private SimpleMeterRegistry meterRegistry;
  private AlertEscalationNotifier notifier;

  @BeforeEach
  void setUp() throws Throwable {
    alertEventMapper = mock(AlertEventMapper.class);
    domainEventPublisher = mock(ConsoleRealtimeDomainEventPublisher.class);
    lockExecutor = mock(LockingTaskExecutor.class);
    meterRegistry = new SimpleMeterRegistry();
    doAnswer(
            inv -> {
              LockingTaskExecutor.Task t = inv.getArgument(0);
              t.call();
              return null;
            })
        .when(lockExecutor)
        .executeWithLock(any(LockingTaskExecutor.Task.class), any());
    notifier =
        new AlertEscalationNotifier(
            alertEventMapper,
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

  @Test
  void shouldSkipPollWhenNoEligibleRows() {
    when(alertEventMapper.selectEscalatedPendingNotify(anyInt())).thenReturn(List.of());

    notifier.poll();

    verify(domainEventPublisher, never()).publishChanged(any(), any(), any(), any());
    verify(alertEventMapper, never()).markEscalationNotified(any(), any(), anyInt(), anyInt());
  }

  @Test
  void shouldPublishEscalatedEventAndBumpWatermark() {
    when(alertEventMapper.selectEscalatedPendingNotify(anyInt()))
        .thenReturn(List.of(escalated(11L, "t1", 2, 1)));
    when(alertEventMapper.markEscalationNotified("t1", 11L, 1, 2)).thenReturn(1);

    notifier.poll();

    ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
    verify(domainEventPublisher)
        .publishChanged(eq("t1"), eq("alerts"), eq("ALERT_ESCALATED"), payloadCaptor.capture());
    assertThat(payloadCaptor.getValue()).isInstanceOf(AlertEscalationNotifyPayload.class);
    AlertEscalationNotifyPayload payload = (AlertEscalationNotifyPayload) payloadCaptor.getValue();
    assertThat(payload.alertId()).isEqualTo(11L);
    assertThat(payload.escalationTier()).isEqualTo(2);
    assertThat(payload.severity()).isEqualTo("CRITICAL");
    assertThat(payload.alertType()).isEqualTo("SLA_BREACH");

    verify(alertEventMapper).markEscalationNotified("t1", 11L, 1, 2);
    assertThat(meterRegistry.counter("batch.alert.escalation.notifications").count())
        .isEqualTo(1.0);
  }

  @Test
  void shouldNotCountWhenWatermarkCasLoses() {
    // 并发 ack / 其它实例抢先通知 → markEscalationNotified 返回 0;事件已发(至少一次),但不重复计数。
    when(alertEventMapper.selectEscalatedPendingNotify(anyInt()))
        .thenReturn(List.of(escalated(12L, "t1", 1, 0)));
    when(alertEventMapper.markEscalationNotified("t1", 12L, 0, 1)).thenReturn(0);

    notifier.poll();

    verify(domainEventPublisher)
        .publishChanged(eq("t1"), eq("alerts"), eq("ALERT_ESCALATED"), any());
    assertThat(meterRegistry.counter("batch.alert.escalation.notifications").count())
        .isEqualTo(0.0);
  }

  @Test
  void shouldSkipRowAlreadyNotifiedAtCurrentTier() {
    // 防御:即便 select 漏过滤,tier <= notifiedTier 也不发。
    when(alertEventMapper.selectEscalatedPendingNotify(anyInt()))
        .thenReturn(List.of(escalated(13L, "t1", 2, 2)));

    notifier.poll();

    verify(domainEventPublisher, never()).publishChanged(any(), any(), any(), any());
    verify(alertEventMapper, never()).markEscalationNotified(any(), any(), anyInt(), anyInt());
  }

  @Test
  void shouldContinueBatchWhenOneRowThrows() {
    when(alertEventMapper.selectEscalatedPendingNotify(anyInt()))
        .thenReturn(List.of(escalated(14L, "t1", 1, 0), escalated(15L, "t2", 1, 0)));
    doThrow(new RuntimeException("publish boom"))
        .when(domainEventPublisher)
        .publishChanged(eq("t1"), eq("alerts"), eq("ALERT_ESCALATED"), any());
    when(alertEventMapper.markEscalationNotified("t2", 15L, 0, 1)).thenReturn(1);

    notifier.poll();

    // 第二条仍被处理
    verify(domainEventPublisher)
        .publishChanged(eq("t2"), eq("alerts"), eq("ALERT_ESCALATED"), any());
    verify(alertEventMapper).markEscalationNotified("t2", 15L, 0, 1);
    // 第一条发布抛异常 → 未推进水位线
    verify(alertEventMapper, never()).markEscalationNotified(eq("t1"), eq(14L), anyInt(), anyInt());
  }

  @Test
  void shouldSkipPollAfterContextClosedWithoutTakingLock() throws Throwable {
    notifier.stopOnContextClosed(new ContextClosedEvent(new StaticApplicationContext()));

    notifier.poll();

    verify(lockExecutor, never()).executeWithLock(any(LockingTaskExecutor.Task.class), any());
    verify(alertEventMapper, never()).selectEscalatedPendingNotify(anyInt());
  }

  @Test
  void shouldPublishOncePerRowAcrossTenants() {
    when(alertEventMapper.selectEscalatedPendingNotify(anyInt()))
        .thenReturn(List.of(escalated(21L, "t1", 1, 0), escalated(22L, "t2", 3, 2)));
    when(alertEventMapper.markEscalationNotified(any(), any(), anyInt(), anyInt())).thenReturn(1);

    notifier.poll();

    verify(domainEventPublisher, times(2))
        .publishChanged(any(), eq("alerts"), eq("ALERT_ESCALATED"), any());
    verify(alertEventMapper).markEscalationNotified("t1", 21L, 0, 1);
    verify(alertEventMapper).markEscalationNotified("t2", 22L, 2, 3);
  }
}
