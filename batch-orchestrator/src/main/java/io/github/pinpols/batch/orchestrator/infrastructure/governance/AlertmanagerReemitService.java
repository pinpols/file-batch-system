package io.github.pinpols.batch.orchestrator.infrastructure.governance;

import io.github.pinpols.batch.common.enums.AlertStatus;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.config.AlertmanagerEmitProperties;
import io.github.pinpols.batch.orchestrator.mapper.AlertEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 按租户读取通知意图并推进公平轮询水位；提交后才异步发送，不以尝试代替成功回执。 */
@Service
@RequiredArgsConstructor
public class AlertmanagerReemitService {
  private final AlertEventMapper mapper;
  private final AlertmanagerEmitPublisher publisher;
  private final AlertmanagerEmitProperties properties;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void reemitTenant(String tenantId) {
    mapper.bindReemitTenant(tenantId);
    int limit = Math.max(1, properties.getReemitBatchSize());
    for (AlertEventEntity alert : mapper.selectActiveForReemit(tenantId, limit)) {
      AlertEventEntity closed =
          mapper.closeEligibleMonitoringAlert(alert, AlertStatus.CLOSED.code());
      if (EmptyChecks.isNull(closed)) {
        schedule(alert, false);
      }
    }
    for (AlertEventEntity alert : mapper.selectClosedPendingNotify(tenantId, limit)) {
      schedule(alert, true);
    }
  }

  private void schedule(AlertEventEntity alert, boolean resolved) {
    if (mapper.markReemitAttempt(alert) == 0) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          publish(alert, resolved);
        }
      });
    } else {
      publish(alert, resolved);
    }
  }

  private void publish(AlertEventEntity alert, boolean resolved) {
    if (resolved) {
      publisher.publishResolved(alert);
    } else {
      publisher.publishFiring(alert);
    }
  }
}
