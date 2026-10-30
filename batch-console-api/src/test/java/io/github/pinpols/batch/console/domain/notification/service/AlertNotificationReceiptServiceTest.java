package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerAlert;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerWebhookPayload;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertNotificationReceiptMapper;
import io.github.pinpols.batch.console.domain.notification.query.AlertNotificationReceiptCommand;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("最终通知回执的实体与租户边界")
class AlertNotificationReceiptServiceTest {
  @Test
  @DisplayName("按每条状态写发生和恢复回执，不写外租户及无平台指纹的技术规则")
  void shouldRecordEachDeliveredStatusForMatchingTenantOnly() {
    AlertNotificationReceiptMapper mapper = mock(AlertNotificationReceiptMapper.class);
    AlertNotificationReceiptService service = new AlertNotificationReceiptService(mapper);
    AlertmanagerAlert firing = alert("tenant-a", "fp-a", "firing");
    AlertmanagerAlert resolved = alert("tenant-a", "fp-b", "resolved");
    AlertmanagerAlert foreign = alert("tenant-b", "fp-c", "firing");
    AlertmanagerAlert technical = new AlertmanagerAlert(
        "firing", Map.of("alertname", "up"), Map.of(), null, null, null, "am-only");
    AlertmanagerWebhookPayload payload = new AlertmanagerWebhookPayload(
        "4",
        "group",
        0,
        "firing",
        "batch-sla",
        Map.of(),
        Map.of("tenant", "tenant-a"),
        Map.of(),
        null,
        List.of(firing, resolved, foreign, technical));

    service.recordDelivered("tenant-a", "batch-sla", payload);

    verify(mapper).bindTenant("tenant-a");
    ArgumentCaptor<AlertNotificationReceiptCommand> commands =
        ArgumentCaptor.forClass(AlertNotificationReceiptCommand.class);
    verify(mapper, times(2)).recordDelivered(commands.capture());
    assertThat(commands.getAllValues())
        .containsExactly(
            new AlertNotificationReceiptCommand(
                "tenant-a", "fp-a", "JOB_RUNNING_TOO_LONG", "batch-sla", "firing"),
            new AlertNotificationReceiptCommand(
                "tenant-a", "fp-b", "JOB_RUNNING_TOO_LONG", "batch-sla", "resolved"));
  }

  private static AlertmanagerAlert alert(String tenantId, String fingerprint, String status) {
    Map<String, String> labels =
        Map.of("tenant", tenantId, "fingerprint", fingerprint, "alertname", "JOB_RUNNING_TOO_LONG");
    return new AlertmanagerAlert(status, labels, Map.of(), null, null, null, "am-fp");
  }
}
