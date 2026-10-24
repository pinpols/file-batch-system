package io.github.pinpols.batch.orchestrator.application.outbox;

import io.github.pinpols.batch.orchestrator.domain.entity.EventDeliveryLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.EventOutboxRetryEntity;
import io.github.pinpols.batch.orchestrator.mapper.EventDeliveryLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.EventOutboxRetryMapper;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists Outbox child audit rows against the complete hot or archived parent identity. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxChildRecordService {

  private final EventDeliveryLogMapper eventDeliveryLogMapper;
  private final EventOutboxRetryMapper eventOutboxRetryMapper;

  @Transactional
  public void recordDelivery(EventDeliveryLogEntity entity) {
    if (eventDeliveryLogMapper.insert(entity) == 1) {
      return;
    }
    if (eventDeliveryLogMapper.insertIntoArchive(entity) == 1) {
      log.warn(
          "late outbox delivery result recorded against archived event: tenant={}, eventId={},"
              + " eventCreatedAt={}, deliveryAttempt={}, status={}",
          entity.getTenantId(),
          entity.getOutboxEventId(),
          entity.getOutboxEventCreatedAt(),
          entity.getDeliveryAttempt(),
          entity.getDeliveryStatus());
      return;
    }
    throw missingParent(entity.getTenantId(), entity.getOutboxEventId());
  }

  @Transactional
  public void recordRetry(EventOutboxRetryEntity entity) {
    if (eventOutboxRetryMapper.insert(entity) == 1) {
      return;
    }
    if (eventOutboxRetryMapper.insertIntoArchive(entity) == 1) {
      log.warn(
          "late outbox retry audit recorded against archived event: tenant={}, eventId={},"
              + " eventCreatedAt={}, retryAttempt={}, status={}",
          entity.getTenantId(),
          entity.getOutboxEventId(),
          entity.getOutboxEventCreatedAt(),
          entity.getPublishAttempt(),
          entity.getRetryStatus());
      return;
    }
    throw missingParent(entity.getTenantId(), entity.getOutboxEventId());
  }

  private static DataIntegrityViolationException missingParent(String tenantId, Long eventId) {
    SQLException cause =
        new SQLException(
            "Outbox child reference does not match a hot or archived parent", "23503");
    return new DataIntegrityViolationException(
        "Outbox event reference is unavailable for tenant " + tenantId + " and event " + eventId,
        cause);
  }
}
