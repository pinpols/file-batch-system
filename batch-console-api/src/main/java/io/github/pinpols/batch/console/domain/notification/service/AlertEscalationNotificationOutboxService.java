package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.notification.entity.AlertEscalationNotificationOutboxEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEscalationNotificationOutboxMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlertEscalationNotificationOutboxService {

  private final AlertEventMapper alertEventMapper;
  private final AlertEscalationNotificationOutboxMapper outboxMapper;

  /**
   * 在同一事务内抢占告警升级通知所有权并写入通知 outbox。
   *
   * <p>只有 {@code markEscalationNotified} CAS 成功的实例才会插入 outbox；CAS 失败表示告警已被 ACK、关闭或其它实例抢先处理。
   */
  @Transactional
  public boolean enqueue(
      AlertEventEntity alert,
      String stream,
      String eventType,
      AlertEscalationNotifier.AlertEscalationNotifyPayload payload) {
    int tier = zeroIfNull(alert.getEscalationTier());
    int notifiedTier = zeroIfNull(alert.getEscalationNotifiedTier());
    if (tier <= notifiedTier) {
      return false;
    }
    int marked = alertEventMapper.markEscalationNotified(
        alert.getTenantId(), alert.getId(), notifiedTier, tier);
    if (marked == 0) {
      return false;
    }
    AlertEscalationNotificationOutboxEntity row = new AlertEscalationNotificationOutboxEntity();
    row.setTenantId(alert.getTenantId());
    row.setAlertEventId(alert.getId());
    row.setEscalationTier(tier);
    row.setStream(stream);
    row.setEventType(eventType);
    row.setPayloadJson(JsonUtils.toJson(payload));
    row.setPublishStatus(OutboxPublishStatus.NEW.code());
    row.setAttemptCount(0);
    row.setNextPublishAt(BatchDateTimeSupport.utcNow());
    int inserted = outboxMapper.insert(row);
    if (inserted != 1) {
      throw new IllegalStateException(
          "alert escalation notification outbox insert affected " + inserted + " rows");
    }
    return true;
  }

  private static int zeroIfNull(Integer value) {
    return EmptyChecks.isNull(value) ? 0 : value;
  }
}
