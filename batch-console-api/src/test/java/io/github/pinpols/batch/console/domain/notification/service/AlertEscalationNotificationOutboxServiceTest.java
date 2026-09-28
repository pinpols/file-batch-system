package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.console.domain.notification.entity.AlertEscalationNotificationOutboxEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEscalationNotificationOutboxMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEventMapper;
import io.github.pinpols.batch.console.domain.notification.service.AlertEscalationNotifier.AlertEscalationNotifyPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AlertEscalationNotificationOutboxServiceTest {

  private AlertEventMapper alertEventMapper;
  private AlertEscalationNotificationOutboxMapper outboxMapper;
  private AlertEscalationNotificationOutboxService service;

  @BeforeEach
  void setUp() {
    alertEventMapper = mock(AlertEventMapper.class);
    outboxMapper = mock(AlertEscalationNotificationOutboxMapper.class);
    when(outboxMapper.insert(any())).thenReturn(1);
    service = new AlertEscalationNotificationOutboxService(alertEventMapper, outboxMapper);
  }

  @Test
  void enqueueMarksWatermarkAndInsertsOutboxWhenCasWins() {
    AlertEventEntity alert = alert(11L, "t1", 2, 1);
    when(alertEventMapper.markEscalationNotified("t1", 11L, 1, 2)).thenReturn(1);

    boolean enqueued = service.enqueue(alert, "alerts", "ALERT_ESCALATED", payload(11L, 2));

    assertThat(enqueued).isTrue();
    ArgumentCaptor<AlertEscalationNotificationOutboxEntity> captor =
        ArgumentCaptor.forClass(AlertEscalationNotificationOutboxEntity.class);
    verify(outboxMapper).insert(captor.capture());
    AlertEscalationNotificationOutboxEntity row = captor.getValue();
    assertThat(row.getTenantId()).isEqualTo("t1");
    assertThat(row.getAlertEventId()).isEqualTo(11L);
    assertThat(row.getEscalationTier()).isEqualTo(2);
    assertThat(row.getStream()).isEqualTo("alerts");
    assertThat(row.getEventType()).isEqualTo("ALERT_ESCALATED");
    assertThat(row.getPublishStatus()).isEqualTo("NEW");
    assertThat(row.getAttemptCount()).isZero();
  }

  @Test
  void enqueueDoesNotInsertOutboxWhenCasLoses() {
    AlertEventEntity alert = alert(12L, "t1", 1, 0);
    when(alertEventMapper.markEscalationNotified("t1", 12L, 0, 1)).thenReturn(0);

    boolean enqueued = service.enqueue(alert, "alerts", "ALERT_ESCALATED", payload(12L, 1));

    assertThat(enqueued).isFalse();
    verify(outboxMapper, never()).insert(any());
  }

  @Test
  void enqueueFailsFastWhenOutboxInsertDoesNotCreateRow() {
    AlertEventEntity alert = alert(14L, "t1", 2, 1);
    when(alertEventMapper.markEscalationNotified("t1", 14L, 1, 2)).thenReturn(1);
    when(outboxMapper.insert(any())).thenReturn(0);

    assertThatThrownBy(() -> service.enqueue(alert, "alerts", "ALERT_ESCALATED", payload(14L, 2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outbox insert affected 0 rows");
  }

  @Test
  void enqueueSkipsRowsAlreadyNotifiedAtCurrentTier() {
    AlertEventEntity alert = alert(13L, "t1", 2, 2);

    boolean enqueued = service.enqueue(alert, "alerts", "ALERT_ESCALATED", payload(13L, 2));

    assertThat(enqueued).isFalse();
    verify(alertEventMapper, never()).markEscalationNotified(any(), any(), anyInt(), anyInt());
    verify(outboxMapper, never()).insert(any());
  }

  private static AlertEventEntity alert(long id, String tenantId, int tier, int notifiedTier) {
    AlertEventEntity alert = new AlertEventEntity();
    alert.setId(id);
    alert.setTenantId(tenantId);
    alert.setAlertType("SLA_BREACH");
    alert.setSeverity("CRITICAL");
    alert.setTitle("job stuck past SLA");
    alert.setEscalationTier(tier);
    alert.setEscalationNotifiedTier(notifiedTier);
    alert.setTraceId("trace-" + id);
    return alert;
  }

  private static AlertEscalationNotifyPayload payload(long alertId, int tier) {
    return new AlertEscalationNotifyPayload(
        alertId, "SLA_BREACH", "CRITICAL", "job stuck past SLA", tier, "trace-" + alertId);
  }
}
