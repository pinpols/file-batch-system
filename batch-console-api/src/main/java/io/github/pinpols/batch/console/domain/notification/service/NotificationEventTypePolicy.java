package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Locale;
import java.util.Set;

/** SLA 告警由已有告警通道管理，不接受没有业务事件生产者的订阅编码。 */
public final class NotificationEventTypePolicy {
  private static final Set<String> SLA_ALERT_TYPES =
      Set.of("JOB_SLA_BREACH", "JOB_SLA_VIOLATION", "JOB_SLA_VIOLATION_ESCALATED");

  private NotificationEventTypePolicy() {}

  public static void requireBusinessEventTypes(String eventTypes) {
    if (EmptyChecks.isNull(eventTypes)) {
      return;
    }
    for (String eventType : eventTypes.split(",")) {
      String trimmedEventType = eventType.trim();
      String normalizedEventType = trimmedEventType.toUpperCase(Locale.ROOT);
      if (SLA_ALERT_TYPES.contains(normalizedEventType)) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.notification.sla_alert_channel");
      }
    }
  }
}
