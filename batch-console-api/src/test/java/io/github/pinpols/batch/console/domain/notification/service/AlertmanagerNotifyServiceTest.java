package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.config.AlertmanagerNotifyProperties;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationChannelMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.service.AlertmanagerNotifyService.AmNotifyOutcome;
import io.github.pinpols.batch.console.domain.notification.web.request.AlertmanagerAlert;
import io.github.pinpols.batch.console.domain.notification.web.request.AlertmanagerWebhookPayload;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlertmanagerNotifyServiceTest {

  @Mock private NotificationChannelMapper channelMapper;
  @Mock private NotificationDeliveryLogMapper deliveryLogMapper;
  @Mock private NotificationSenderRegistry senderRegistry;
  @Mock private WebhookDispatcher webhookDispatcher;
  @Mock private NotificationSender sender;

  private AlertmanagerNotifyService service;

  @BeforeEach
  void setUp() {
    AlertmanagerNotifyProperties properties = new AlertmanagerNotifyProperties();
    properties.setTenantId("system");
    properties.setMaxAlerts(50);
    service =
        new AlertmanagerNotifyService(
            properties,
            channelMapper,
            deliveryLogMapper,
            senderRegistry,
            webhookDispatcher,
            new AlertmanagerAlertRenderer());
  }

  private AlertmanagerWebhookPayload payload(String receiver) {
    return new AlertmanagerWebhookPayload(
        "4",
        "gk",
        0,
        "firing",
        receiver,
        Map.of(),
        Map.of("alertname", "X", "severity", "critical"),
        Map.of(),
        null,
        List.of(
            new AlertmanagerAlert(
                "firing", Map.of("alertname", "X"), Map.of(), null, null, null, "fp")));
  }

  @Test
  void deliversToResolvedSenderAndLogsSuccess() {
    when(channelMapper.selectByCode("system", "batch-dispatch"))
        .thenReturn(Map.of("channel_type", "WECOM", "config_json", "{\"url\":\"https://x\"}"));
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any())).thenReturn(WebhookDeliveryResult.ok());

    AmNotifyOutcome outcome = service.deliver("batch-dispatch", payload("batch-dispatch"));

    assertThat(outcome.delivered()).isTrue();
    assertThat(outcome.status()).isEqualTo("SUCCESS");
    ArgumentCaptor<NotificationMessage> msg = ArgumentCaptor.forClass(NotificationMessage.class);
    verify(sender).send(msg.capture());
    assertThat(msg.getValue().channelType()).isEqualTo("WECOM");
    assertThat(msg.getValue().payload().eventType())
        .isEqualTo("[FIRING] batch-dispatch · X (1 alert)");

    ArgumentCaptor<Map<String, Object>> log = ArgumentCaptor.forClass(Map.class);
    verify(deliveryLogMapper).insert(log.capture());
    assertThat(log.getValue()).containsEntry("deliveryStatus", "SUCCESS");
    assertThat(log.getValue()).containsEntry("channelCode", "batch-dispatch");
    assertThat(log.getValue()).containsEntry("eventType", "ALERTMANAGER");
  }

  @Test
  void logsFailedWhenSenderFails() {
    when(channelMapper.selectByCode("system", "batch-sla"))
        .thenReturn(Map.of("channel_type", "DINGTALK", "config_json", "{}"));
    when(senderRegistry.resolve("DINGTALK")).thenReturn(sender);
    when(sender.send(any())).thenReturn(WebhookDeliveryResult.failure(500, "boom"));

    AmNotifyOutcome outcome = service.deliver("batch-sla", payload("batch-sla"));

    assertThat(outcome.delivered()).isFalse();
    assertThat(outcome.status()).isEqualTo("FAILED");
    assertThat(outcome.detail()).isEqualTo("boom");
    ArgumentCaptor<Map<String, Object>> log = ArgumentCaptor.forClass(Map.class);
    verify(deliveryLogMapper).insert(log.capture());
    assertThat(log.getValue()).containsEntry("deliveryStatus", "FAILED");
    assertThat(log.getValue()).containsEntry("errorMessage", "boom");
  }

  @Test
  void skipsWhenNoChannelConfiguredAndDoesNotLog() {
    when(channelMapper.selectByCode("system", "batch-unknown")).thenReturn(null);

    AmNotifyOutcome outcome = service.deliver("batch-unknown", payload("batch-unknown"));

    assertThat(outcome.delivered()).isFalse();
    assertThat(outcome.status()).isEqualTo("SKIPPED");
    verify(deliveryLogMapper, never()).insert(any());
    verify(senderRegistry, never()).resolve(anyString());
  }

  @Test
  void routesWebhookChannelThroughDispatcher() {
    when(channelMapper.selectByCode("system", "batch-default"))
        .thenReturn(Map.of("channel_type", "WEBHOOK", "config_json", "{\"url\":\"https://hook\"}"));
    when(webhookDispatcher.attemptDelivery(any(), any(), anyString()))
        .thenReturn(WebhookDeliveryResult.ok());

    AmNotifyOutcome outcome = service.deliver("batch-default", payload("batch-default"));

    assertThat(outcome.delivered()).isTrue();
    verify(webhookDispatcher).attemptDelivery(any(), any(), anyString());
    verify(senderRegistry, never()).resolve(eq("WEBHOOK"));
  }
}
