package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.config.AlertmanagerNotifyProperties;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerAlert;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerWebhookPayload;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationChannelMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.service.AlertmanagerNotifyService.AmNotifyOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("告警通知投递服务: 渠道解析, 租户反查与投递日志落库")
class AlertmanagerNotifyServiceTest {

  @Mock
  private NotificationChannelMapper channelMapper;

  @Mock
  private NotificationDeliveryLogMapper deliveryLogMapper;

  @Mock
  private NotificationSenderRegistry senderRegistry;

  @Mock
  private WebhookDispatcher webhookDispatcher;

  @Mock
  private NotificationSender sender;

  private AlertmanagerNotifyService service;
  private MeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    AlertmanagerNotifyProperties properties = new AlertmanagerNotifyProperties();
    properties.setTenantId("system");
    properties.setMaxAlerts(50);
    meterRegistry = new SimpleMeterRegistry();
    service = new AlertmanagerNotifyService(
        properties,
        channelMapper,
        deliveryLogMapper,
        senderRegistry,
        webhookDispatcher,
        new AlertmanagerAlertRenderer(),
        meterRegistry);
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
        List.of(new AlertmanagerAlert(
            "firing", Map.of("alertname", "X"), Map.of(), null, null, null, "fp")));
  }

  @Test
  @DisplayName("解析到渠道发送器后投递成功, 并按成功状态写入投递日志")
  void shouldDeliverAndLogSuccess_whenChannelResolved() {
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
    // eventType 是稳定常量(进 X-Batch-Event-Type 头),人类可读 title 只在 body/structured.text。
    assertThat(msg.getValue().payload().eventType()).isEqualTo("ALERTMANAGER");
    assertThat(msg.getValue().payload().data().toString()).contains("[FIRING] batch-dispatch · X");

    ArgumentCaptor<Map<String, Object>> log = ArgumentCaptor.captor();
    verify(deliveryLogMapper).insert(log.capture());
    assertThat(log.getValue()).containsEntry("deliveryStatus", "SUCCESS");
    assertThat(log.getValue()).containsEntry("channelCode", "batch-dispatch");
    assertThat(log.getValue()).containsEntry("eventType", "ALERTMANAGER");
  }

  @Test
  @DisplayName("发送器返回失败时结论为失败, 日志记录失败原因")
  void shouldLogFailed_whenSenderReturnsFailure() {
    when(channelMapper.selectByCode("system", "batch-sla"))
        .thenReturn(Map.of("channel_type", "DINGTALK", "config_json", "{}"));
    when(senderRegistry.resolve("DINGTALK")).thenReturn(sender);
    when(sender.send(any())).thenReturn(WebhookDeliveryResult.failure(500, "boom"));

    AmNotifyOutcome outcome = service.deliver("batch-sla", payload("batch-sla"));

    assertThat(outcome.delivered()).isFalse();
    assertThat(outcome.status()).isEqualTo("FAILED");
    assertThat(outcome.detail()).isEqualTo("boom");
    ArgumentCaptor<Map<String, Object>> log = ArgumentCaptor.captor();
    verify(deliveryLogMapper).insert(log.capture());
    assertThat(log.getValue()).containsEntry("deliveryStatus", "FAILED");
    assertThat(log.getValue()).containsEntry("errorMessage", "boom");
  }

  @Test
  @DisplayName("未配置渠道时跳过投递且不写日志, 缺渠道计数自增")
  void shouldSkipWithoutLog_whenChannelMissing() {
    when(channelMapper.selectByCode("system", "batch-unknown")).thenReturn(null);

    AmNotifyOutcome outcome = service.deliver("batch-unknown", payload("batch-unknown"));

    assertThat(outcome.delivered()).isFalse();
    assertThat(outcome.status()).isEqualTo("SKIPPED");
    verify(deliveryLogMapper, never()).insert(any());
    verify(senderRegistry, never()).resolve(anyString());
    // I-1: 缺渠道不再静默,计数器自增供 Prometheus 再告警。
    assertThat(meterRegistry
            .counter("am.notify.skipped", "receiver", "batch-unknown")
            .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("告警名含换行注入时, 事件类型仍是稳定常量且不含换行")
  void shouldKeepStableEventType_whenAlertnameHasLineBreak() {
    when(channelMapper.selectByCode("system", "batch-dispatch"))
        .thenReturn(Map.of("channel_type", "WECOM", "config_json", "{}"));
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any())).thenReturn(WebhookDeliveryResult.ok());
    AlertmanagerWebhookPayload evil = new AlertmanagerWebhookPayload(
        "4",
        "gk",
        0,
        "firing",
        "batch-dispatch",
        Map.of(),
        Map.of("alertname", "Evil\r\nX-Injected: 1"),
        Map.of(),
        null,
        List.of(new AlertmanagerAlert(
            "firing",
            Map.of("alertname", "Evil\r\nX-Injected: 1"),
            Map.of(),
            null,
            null,
            null,
            "fp")));

    service.deliver("batch-dispatch", evil);

    // M-2: eventType 进 X-Batch-Event-Type 头,必须是无 CR/LF 的稳定常量,否则 JDK http client 抛错静默失败。
    ArgumentCaptor<NotificationMessage> msg = ArgumentCaptor.forClass(NotificationMessage.class);
    verify(sender).send(msg.capture());
    assertThat(msg.getValue().payload().eventType()).isEqualTo("ALERTMANAGER");
    assertThat(msg.getValue().payload().eventType()).doesNotContain("\r", "\n");
  }

  @Test
  @DisplayName("公共标签带租户时按该租户反查渠道, 不再固定用配置租户")
  void shouldLookUpTenant_whenCommonLabelPresent() {
    // §4/§7 硬前置:按 payload 的 tenant label 反查该租户渠道,而非一律落 system。
    AlertmanagerWebhookPayload payload = new AlertmanagerWebhookPayload(
        "4",
        "gk",
        0,
        "firing",
        "batch-sla",
        Map.of(),
        Map.of("alertname", "X", "severity", "critical", "tenant", "ta"),
        Map.of(),
        null,
        List.of(new AlertmanagerAlert(
            "firing", Map.of("alertname", "X", "tenant", "ta"), Map.of(), null, null, null, "fp")));
    when(channelMapper.selectByCode("ta", "batch-sla"))
        .thenReturn(Map.of("channel_type", "WECOM", "config_json", "{}"));
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any())).thenReturn(WebhookDeliveryResult.ok());

    AmNotifyOutcome outcome = service.deliver("batch-sla", payload);

    assertThat(outcome.delivered()).isTrue();
    // 反查命中租户 ta 的渠道,不是 system。
    verify(channelMapper).selectByCode("ta", "batch-sla");
  }

  @Test
  @DisplayName("公共标签无租户时, 回退到配置的默认租户查询渠道")
  void shouldFallBackToConfiguredTenant_whenLabelAbsent() {
    when(channelMapper.selectByCode("system", "batch-sla"))
        .thenReturn(Map.of("channel_type", "WECOM", "config_json", "{}"));
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any())).thenReturn(WebhookDeliveryResult.ok());

    // payload() 的 commonLabels 无 tenant → 回退到 properties.tenantId=system。
    service.deliver("batch-sla", payload("batch-sla"));

    verify(channelMapper).selectByCode("system", "batch-sla");
  }

  @Test
  @DisplayName("渠道为回调类型时走分发器投递, 不解析普通发送器")
  void shouldRouteToDispatcher_whenChannelIsCallback() {
    when(channelMapper.selectByCode("system", "batch-default"))
        .thenReturn(Map.of("channel_type", "WEBHOOK", "config_json", "{\"url\":\"https://hook\"}"));
    when(webhookDispatcher.attemptDelivery(any(), any(), anyString()))
        .thenReturn(WebhookDeliveryResult.ok());

    AmNotifyOutcome outcome = service.deliver("batch-default", payload("batch-default"));

    assertThat(outcome.delivered()).isTrue();
    verify(webhookDispatcher).attemptDelivery(any(), any(), anyString());
    verify(senderRegistry, never()).resolve("WEBHOOK");
  }
}
