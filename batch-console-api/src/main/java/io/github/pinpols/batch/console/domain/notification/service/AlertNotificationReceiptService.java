package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerAlert;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerWebhookPayload;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertNotificationReceiptMapper;
import io.github.pinpols.batch.console.domain.notification.query.AlertNotificationReceiptCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 最终渠道明确成功后，独立持久化实际已投递列表的逐条回执。 */
@Service
@RequiredArgsConstructor
public class AlertNotificationReceiptService {
  private final AlertNotificationReceiptMapper mapper;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordDelivered(
      String tenantId, String receiver, AlertmanagerWebhookPayload payload) {
    mapper.bindTenant(tenantId);
    for (AlertmanagerAlert alert : payload.safeAlerts()) {
      String fingerprint = alert.label("fingerprint");
      String alertTenant = alert.label("tenant");
      if (!tenantId.equals(alertTenant) || EmptyChecks.isBlank(fingerprint)) {
        continue;
      }
      AlertNotificationReceiptCommand command = new AlertNotificationReceiptCommand(
          tenantId, fingerprint, alert.label("alertname"), receiver, alert.status());
      mapper.recordDelivered(command);
    }
  }
}
