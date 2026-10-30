package io.github.pinpols.batch.orchestrator.infrastructure.governance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.AlertStatus;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.orchestrator.config.AlertmanagerEmitProperties;
import io.github.pinpols.batch.orchestrator.mapper.AlertEventMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

@DisplayName("告警重发的关闭回执、状态竞争与提交边界")
class AlertmanagerReemitServiceTest {
  @Test
  @DisplayName("真实恢复且已有发生回执时先关闭当前实体，不再刷新发生通知")
  void shouldResolveEligibleMonitoringAlertBeforeRefreshingFiring() {
    AlertEventMapper mapper = mock(AlertEventMapper.class);
    AlertmanagerEmitPublisher publisher = mock(AlertmanagerEmitPublisher.class);
    AlertEventEntity active = closed();
    active.setStatus("ACKED");
    active.setClosedAt(null);
    AlertEventEntity recovered = closed();
    when(mapper.selectActiveForReemit("tenant-a", 200)).thenReturn(List.of(active));
    when(mapper.closeEligibleMonitoringAlert(active, AlertStatus.CLOSED.code()))
        .thenReturn(recovered);
    when(mapper.selectClosedPendingNotify("tenant-a", 200)).thenReturn(List.of(recovered));
    when(mapper.markReemitAttempt(recovered)).thenReturn(1);
    AlertmanagerReemitService service =
        new AlertmanagerReemitService(mapper, publisher, new AlertmanagerEmitProperties());

    service.reemitTenant("tenant-a");

    verify(publisher).publishResolved(recovered);
    verify(publisher, never()).publishFiring(any());
  }

  @Test
  @DisplayName("没有活动告警也补发尚未确认成功的关闭通知")
  void shouldRetryResolvedEvenWithoutActiveAlerts() {
    AlertEventMapper mapper = mock(AlertEventMapper.class);
    AlertmanagerEmitPublisher publisher = mock(AlertmanagerEmitPublisher.class);
    AlertEventEntity closed = closed();
    when(mapper.selectClosedPendingNotify("tenant-a", 200)).thenReturn(List.of(closed));
    when(mapper.markReemitAttempt(closed)).thenReturn(1);
    AlertmanagerReemitService service =
        new AlertmanagerReemitService(mapper, publisher, new AlertmanagerEmitProperties());

    service.reemitTenant("tenant-a");

    verify(mapper).bindReemitTenant("tenant-a");
    verify(publisher).publishResolved(closed);
    verify(publisher, never()).publishFiring(any());
  }

  @Test
  @DisplayName("候选状态在轮询期间变化时不发送旧通知")
  void shouldNotSendWhenStateCasFails() {
    AlertEventMapper mapper = mock(AlertEventMapper.class);
    AlertmanagerEmitPublisher publisher = mock(AlertmanagerEmitPublisher.class);
    AlertEventEntity closed = closed();
    when(mapper.selectClosedPendingNotify("tenant-a", 200)).thenReturn(List.of(closed));
    AlertmanagerReemitService service =
        new AlertmanagerReemitService(mapper, publisher, new AlertmanagerEmitProperties());

    service.reemitTenant("tenant-a");

    verifyNoInteractions(publisher);
  }

  @Test
  @DisplayName("事务提交前不发送，提交后按原状态补发")
  void shouldPublishOnlyAfterTransactionCommit() {
    AlertEventMapper mapper = mock(AlertEventMapper.class);
    AlertmanagerEmitPublisher publisher = mock(AlertmanagerEmitPublisher.class);
    AlertEventEntity closed = closed();
    when(mapper.selectClosedPendingNotify("tenant-a", 200)).thenReturn(List.of(closed));
    when(mapper.markReemitAttempt(closed)).thenReturn(1);
    AlertmanagerReemitService service =
        new AlertmanagerReemitService(mapper, publisher, new AlertmanagerEmitProperties());
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.reemitTenant("tenant-a");
      verifyNoInteractions(publisher);
      TransactionSynchronizationUtils.triggerAfterCommit();
      verify(publisher).publishResolved(closed);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("事务回滚不发送幽灵恢复通知")
  void shouldNotPublishOnTransactionRollback() {
    AlertEventMapper mapper = mock(AlertEventMapper.class);
    AlertmanagerEmitPublisher publisher = mock(AlertmanagerEmitPublisher.class);
    AlertEventEntity closed = closed();
    when(mapper.selectClosedPendingNotify("tenant-a", 200)).thenReturn(List.of(closed));
    when(mapper.markReemitAttempt(closed)).thenReturn(1);
    AlertmanagerReemitService service =
        new AlertmanagerReemitService(mapper, publisher, new AlertmanagerEmitProperties());
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.reemitTenant("tenant-a");
      TransactionSynchronizationUtils.triggerAfterCompletion(
          TransactionSynchronization.STATUS_ROLLED_BACK);
      verifyNoInteractions(publisher);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private static AlertEventEntity closed() {
    AlertEventEntity entity = new AlertEventEntity();
    entity.setTenantId("tenant-a");
    entity.setId(42L);
    entity.setStatus("CLOSED");
    entity.setClosedAt(Instant.parse("2026-10-25T00:00:00Z"));
    return entity;
  }
}
