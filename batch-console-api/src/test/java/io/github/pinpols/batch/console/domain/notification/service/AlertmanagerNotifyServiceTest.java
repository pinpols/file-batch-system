package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.AlertmanagerNotifyProperties;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerAlert;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerWebhookPayload;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationChannelMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.param.NotificationDeliveryLogInsertParam;
import io.github.pinpols.batch.console.domain.notification.service.AlertmanagerNotifyService.AmNotifyOutcome;
import io.github.pinpols.batch.console.domain.notification.view.NotificationChannelRow;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

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

  @Mock
  private AlertNotificationReceiptService receiptService;

  private AlertmanagerNotifyProperties properties;
  private AlertmanagerNotifyService service;
  private MeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    properties = new AlertmanagerNotifyProperties();
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
        meterRegistry,
        receiptService);
  }

  @Test
  @DisplayName("接入关闭时应用服务直接拒绝投递且不查询渠道")
  void shouldRejectDirectDeliveryWhenDisabled() {
    properties.setEnabled(false);
    AlertmanagerWebhookPayload alertPayload = payload("batch-sla");
    assertThatThrownBy(() -> service.deliver("batch-sla", alertPayload))
        .isInstanceOf(BizException.class)
        .satisfies(exception -> assertThat(((BizException) exception).getCode())
            .isEqualTo(ResultCode.SERVICE_UNAVAILABLE));
    verify(channelMapper, never()).selectByCode(anyString(), anyString());
  }

  @Test
  @DisplayName("禁用渠道不调用发送器或回调, 不写成功回执")
  void shouldSkipDelivery_whenChannelDisabled() {
    NotificationChannelRow disabled = new NotificationChannelRow(
        1L, "system", "batch-sla", "停用渠道", "WEBHOOK", "{}", false, null, null, null, null);
    when(channelMapper.selectByCode("system", "batch-sla")).thenReturn(disabled);

    AmNotifyOutcome outcome = service.deliver("batch-sla", payload("batch-sla"));

    assertThat(outcome.delivered()).isFalse();
    assertThat(outcome.status()).isEqualTo("DISABLED");
    verify(webhookDispatcher, never()).attemptDelivery(any(), any(), anyString());
    verify(senderRegistry, never()).resolve(anyString());
    verify(receiptService, never()).recordDelivered(anyString(), anyString(), any());
    verify(deliveryLogMapper, never()).insert(any());
  }

  @Test
  @DisplayName("分组超出正文上限时分批完整投递，每批成功后记录实际列表回执")
  void shouldDeliverEveryAlertInBoundedBatches() {
    properties.setMaxAlerts(2);
    when(channelMapper.selectByCode("system", "batch-sla")).thenReturn(channelRow("WECOM", "{}"));
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any())).thenReturn(WebhookDeliveryResult.ok());
    AlertmanagerWebhookPayload payload = monitoredPayload("firing", 5);

    AmNotifyOutcome result = service.deliver("batch-sla", payload);
    assertThat(result.delivered()).isTrue();

    ArgumentCaptor<NotificationMessage> messages =
        ArgumentCaptor.forClass(NotificationMessage.class);
    verify(sender, times(3)).send(messages.capture());
    assertThat(messages.getAllValues())
        .extracting(message -> message.payload().data().toString())
        .anySatisfy(text -> assertThat(text).contains("实例-4"));
    verify(receiptService, times(3)).recordDelivered(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("最终渠道成功但回执持久化失败时仍返回可重试失败")
  void shouldRequireDurableReceiptBeforeAcknowledgingSuccess() {
    when(channelMapper.selectByCode("system", "batch-sla")).thenReturn(channelRow("WECOM", "{}"));
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any())).thenReturn(WebhookDeliveryResult.ok());
    doThrow(new IllegalStateException("injected receipt persistence failure"))
        .when(receiptService)
        .recordDelivered(anyString(), anyString(), any());

    AmNotifyOutcome result = service.deliver("batch-sla", monitoredPayload("firing", 1));

    assertThat(result.delivered()).isFalse();
    assertThat(result.status()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("同一批重试沿用通知身份，发生与恢复通知身份不同")
  void shouldKeepRetryIdentityAndSeparateResolvedNotification() {
    when(channelMapper.selectByCode("system", "batch-sla")).thenReturn(channelRow("WECOM", "{}"));
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any())).thenReturn(WebhookDeliveryResult.ok());

    service.deliver("batch-sla", monitoredPayload("firing", 1));
    service.deliver("batch-sla", monitoredPayload("firing", 1));
    service.deliver("batch-sla", monitoredPayload("resolved", 1));

    ArgumentCaptor<NotificationMessage> messages =
        ArgumentCaptor.forClass(NotificationMessage.class);
    verify(sender, times(3)).send(messages.capture());
    List<String> cursors = messages.getAllValues().stream()
        .map(message -> message.payload().cursor())
        .toList();
    assertThat(cursors.get(0)).isEqualTo(cursors.get(1)).isNotEqualTo(cursors.get(2));
  }

  @Test
  @DisplayName("混合租户分组在查询渠道前拒绝，不向其他租户发送通知")
  void shouldRejectMixedTenantGroupBeforeChannelLookup() {
    Map<String, String> labels = Map.of("tenant", "another-tenant", "alertname", "X");
    AlertmanagerAlert foreign =
        new AlertmanagerAlert("firing", labels, Map.of(), null, null, null, "fp");
    AlertmanagerWebhookPayload mixed = new AlertmanagerWebhookPayload(
        "4",
        "mixed",
        0,
        "firing",
        "batch-sla",
        Map.of(),
        Map.of("tenant", "system"),
        Map.of(),
        null,
        List.of(foreign));

    AmNotifyOutcome outcome = service.deliver("batch-sla", mixed);

    assertThat(outcome.status()).isEqualTo("FAILED");
    verify(channelMapper, never()).selectByCode(anyString(), anyString());
    verify(receiptService, never()).recordDelivered(anyString(), anyString(), any());
  }

  private static AlertmanagerWebhookPayload monitoredPayload(String status, int count) {
    List<AlertmanagerAlert> alerts = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Map<String, String> labels = Map.of(
          "tenant",
          "system",
          "fingerprint",
          "persisted-fingerprint-" + i,
          "alertname",
          "JOB_NOT_STARTED_BY_DEADLINE");
      Map<String, String> annotations = Map.of("summary", "实例-" + i);
      AlertmanagerAlert alert = new AlertmanagerAlert(
          status, labels, annotations, "2026-10-25T00:00:00Z", null, null, "am-fingerprint-" + i);
      alerts.add(alert);
    }
    return new AlertmanagerWebhookPayload(
        "4",
        "same-am-group",
        0,
        status,
        "batch-sla",
        Map.of(),
        Map.of("tenant", "system"),
        Map.of(),
        null,
        alerts);
  }

  private static NotificationChannelRow channelRow(String channelType, String configJson) {
    return new NotificationChannelRow(
        1L,
        "system",
        "batch-dispatch",
        "batch-dispatch",
        channelType,
        configJson,
        true,
        "op",
        "op",
        null,
        null);
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
        .thenReturn(channelRow("WECOM", "{\"url\":\"https://x\"}"));
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

    ArgumentCaptor<NotificationDeliveryLogInsertParam> log = ArgumentCaptor.captor();
    verify(deliveryLogMapper).insert(log.capture());
    assertThat(log.getValue().deliveryStatus()).isEqualTo("SUCCESS");
    assertThat(log.getValue().channelCode()).isEqualTo("batch-dispatch");
    assertThat(log.getValue().eventType()).isEqualTo("ALERTMANAGER");
  }

  @Test
  @DisplayName("发送器返回失败时结论为失败, 日志记录失败原因")
  void shouldLogFailed_whenSenderReturnsFailure() {
    when(channelMapper.selectByCode("system", "batch-sla"))
        .thenReturn(channelRow("DINGTALK", "{}"));
    when(senderRegistry.resolve("DINGTALK")).thenReturn(sender);
    when(sender.send(any())).thenReturn(WebhookDeliveryResult.failure(500, "boom"));

    AmNotifyOutcome outcome = service.deliver("batch-sla", payload("batch-sla"));

    assertThat(outcome.delivered()).isFalse();
    assertThat(outcome.status()).isEqualTo("FAILED");
    assertThat(outcome.detail()).isEqualTo("boom");
    ArgumentCaptor<NotificationDeliveryLogInsertParam> log = ArgumentCaptor.captor();
    verify(deliveryLogMapper).insert(log.capture());
    assertThat(log.getValue().deliveryStatus()).isEqualTo("FAILED");
    assertThat(log.getValue().errorMessage()).isEqualTo("boom");
    verify(receiptService, never()).recordDelivered(anyString(), anyString(), any());
    assertThat(
            meterRegistry.counter("am.notify.failed", "receiver", "batch-sla").count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("发送器失败摘要的凭据不进入告警日志、审计行或投递结果")
  void shouldMaskSenderFailureInLogAuditAndOutcome() {
    when(channelMapper.selectByCode("system", "batch-sla"))
        .thenReturn(channelRow("DINGTALK", "{}"));
    when(senderRegistry.resolve("DINGTALK")).thenReturn(sender);
    when(sender.send(any()))
        .thenReturn(WebhookDeliveryResult.failure(
            500, "password=stored-secret; ｔｏｋｅｎ=unicode-secret; retry later"));
    Logger logger = (Logger) LoggerFactory.getLogger(AlertmanagerNotifyService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      AmNotifyOutcome outcome = service.deliver("batch-sla", payload("batch-sla"));
      ArgumentCaptor<NotificationDeliveryLogInsertParam> row = ArgumentCaptor.captor();
      verify(deliveryLogMapper).insert(row.capture());

      assertThat(outcome.status()).isEqualTo("FAILED");
      assertThat(outcome.detail())
          .contains("password=****", "token=****", "retry later")
          .doesNotContain("stored-secret", "unicode-secret");
      assertThat(row.getValue().errorMessage())
          .contains("password=****", "token=****")
          .doesNotContain("stored-secret", "unicode-secret");
      assertThat(appender.list).isNotEmpty();
      assertThat(appender.list)
          .extracting(ILoggingEvent::getFormattedMessage)
          .allSatisfy(
              message -> assertThat(message).doesNotContain("stored-secret", "unicode-secret"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("回执持久化异常不把原始堆栈和凭据写入告警日志")
  void shouldMaskReceiptPersistenceFailureInLog() {
    when(channelMapper.selectByCode("system", "batch-sla")).thenReturn(channelRow("WECOM", "{}"));
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any())).thenReturn(WebhookDeliveryResult.ok());
    doThrow(new IllegalStateException("token=receipt-secret"))
        .when(receiptService)
        .recordDelivered(anyString(), anyString(), any());
    Logger logger = (Logger) LoggerFactory.getLogger(AlertmanagerNotifyService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      AmNotifyOutcome outcome = service.deliver("batch-sla", payload("batch-sla"));

      assertThat(outcome.status()).isEqualTo("FAILED");
      assertThat(appender.list).hasSize(1);
      ILoggingEvent event = appender.list.getFirst();
      assertThat(event.getFormattedMessage()).doesNotContain("receipt-secret");
      assertThat(event.getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("投递审计写入异常不把原始堆栈和凭据写入告警日志")
  void shouldMaskDeliveryAuditFailureInLog() {
    when(channelMapper.selectByCode("system", "batch-sla")).thenReturn(channelRow("WECOM", "{}"));
    when(senderRegistry.resolve("WECOM")).thenReturn(sender);
    when(sender.send(any())).thenReturn(WebhookDeliveryResult.ok());
    when(deliveryLogMapper.insert(any()))
        .thenThrow(new IllegalStateException("token=audit-secret"));
    Logger logger = (Logger) LoggerFactory.getLogger(AlertmanagerNotifyService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      AmNotifyOutcome outcome = service.deliver("batch-sla", payload("batch-sla"));

      assertThat(outcome.status()).isEqualTo("SUCCESS");
      assertThat(appender.list)
          .anySatisfy(event ->
              assertThat(event.getFormattedMessage()).contains("delivery log persist failed"))
          .allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("audit-secret");
            assertThat(event.getThrowableProxy()).isNull();
          });
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
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
        .thenReturn(channelRow("WECOM", "{}"));
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
    when(channelMapper.selectByCode("ta", "batch-sla")).thenReturn(channelRow("WECOM", "{}"));
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
    when(channelMapper.selectByCode("system", "batch-sla")).thenReturn(channelRow("WECOM", "{}"));
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
        .thenReturn(channelRow("WEBHOOK", "{\"url\":\"https://hook\"}"));
    when(webhookDispatcher.attemptDelivery(any(), any(), anyString()))
        .thenReturn(WebhookDeliveryResult.ok());

    AmNotifyOutcome outcome = service.deliver("batch-default", payload("batch-default"));

    assertThat(outcome.delivered()).isTrue();
    verify(webhookDispatcher).attemptDelivery(any(), any(), anyString());
    verify(senderRegistry, never()).resolve("WEBHOOK");
  }
}
