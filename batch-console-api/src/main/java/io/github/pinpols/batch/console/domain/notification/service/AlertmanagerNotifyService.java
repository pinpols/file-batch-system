package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.AlertFingerprints;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.config.AlertmanagerNotifyProperties;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerAlert;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.AlertmanagerWebhookPayload;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationChannelMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.param.NotificationDeliveryLogInsertParam;
import io.github.pinpols.batch.console.domain.notification.view.NotificationChannelRow;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

/**
 * Alertmanager 出口投递编排:接住 AM webhook → 按 {@code receiver} 路由到 {@code notification_channel} → 复用既有多渠道
 * sender(WEBHOOK 走 {@link WebhookDispatcher} 自带 SSRF + 超时;其余走 {@link NotificationSenderRegistry})→
 * 落 {@code notification_delivery_log}(复用 #775 同款投递日志路径)。
 *
 * <p>路由方案 A(首版):{@code receiver} 路径变量直接映射 {@code notification_channel.channel_code},反查该租户(见 {@link
 * AlertmanagerNotifyProperties#getTenantId()})下的渠道。渠道缺失 → 只 warn + 返回 SKIPPED(off 关键路径,不落库, 因
 * delivery_status CHECK 只容 SUCCESS/FAILED);渠道存在 → 渲染后投递并落 SUCCESS/FAILED 一条日志。
 *
 * <p>方案 B(消费 {@code alert_routing_config} 表做 receiver→channel 映射)见任务报告后续建议,首版不做。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AlertmanagerNotifyService {

  private static final String CHANNEL_TYPE_WEBHOOK = "WEBHOOK";
  private static final String EVENT_TYPE = "ALERTMANAGER";

  /** 缺渠道丢弃告警的计数器名(供 Prometheus 对"关于告警的告警"再告警)。 */
  private static final String METRIC_SKIPPED = "am.notify.skipped";
  /** 已匹配渠道但最终投递失败的计数器名。 */
  private static final String METRIC_FAILED = "am.notify.failed";

  private final AlertmanagerNotifyProperties properties;
  private final NotificationChannelMapper channelMapper;
  private final NotificationDeliveryLogMapper deliveryLogMapper;
  private final NotificationSenderRegistry senderRegistry;
  private final WebhookDispatcher webhookDispatcher;
  private final AlertmanagerAlertRenderer renderer;
  private final MeterRegistry meterRegistry;
  private final AlertNotificationReceiptService receiptService;

  /** 投递结果(供接收端回执;delivered=false 且 status=SKIPPED 表示无对应渠道)。 */
  public record AmNotifyOutcome(
      String receiver, String channelCode, boolean delivered, String status, String detail) {
    private static final String DISABLED_STATUS = "DISABLED";

    /** 主动禁用是已接收但不发送, 不记送达也不要求上游重试。 */
    public boolean intentionallyDisabled() {
      return DISABLED_STATUS.equals(status);
    }

    public static AmNotifyOutcome disabled(String receiver, String channelCode) {
      return new AmNotifyOutcome(receiver, channelCode, false, DISABLED_STATUS, "channel disabled");
    }
  }

  /** AM alert 携带租户维度的 label 名(见迁移方案 §4:AM 单实例靠 label 携带租户)。 */
  private static final String LABEL_TENANT = "tenant";

  /** 接住一个 receiver 的 AM 告警并投递。绝不抛异常(off 关键路径),失败折叠进 outcome + 日志。 */
  public AmNotifyOutcome deliver(String receiver, AlertmanagerWebhookPayload payload) {
    String tenantId = resolveTenant(payload);
    String channelCode = receiver;
    for (AlertmanagerAlert alert : payload.safeAlerts()) {
      String alertTenant = alert.label(LABEL_TENANT);
      if (hasText(alertTenant) && !tenantId.equals(alertTenant.trim())) {
        return new AmNotifyOutcome(
            receiver, channelCode, false, "FAILED", "mixed tenant alert group");
      }
    }
    NotificationChannelRow channel = channelMapper.selectByCode(tenantId, channelCode);
    if (channel == null) {
      // 固定标识符 am_notify_skipped 便于日志告警规则反查;计数器供 Prometheus 再告警。
      // 告警系统最不该有的失败:告警渠道没预建 → 告警静默蒸发。运维须为每个 receiver 预建同名渠道。
      meterRegistry.counter(METRIC_SKIPPED, "receiver", receiver).increment();
      log.warn(
          "am_notify_skipped receiver={} tenantId={} reason=no_channel",
          LogSanitizer.value(receiver),
          LogSanitizer.value(tenantId));
      return new AmNotifyOutcome(
          receiver, channelCode, false, "SKIPPED", "no channel configured for receiver");
    }

    if (!Boolean.TRUE.equals(channel.enabled())) {
      log.debug(
          "am_notify_disabled receiver={} tenantId={}",
          LogSanitizer.value(receiver),
          LogSanitizer.value(tenantId));
      return AmNotifyOutcome.disabled(receiver, channelCode);
    }

    int batchSize = Math.max(1, properties.getMaxAlerts());
    List<AlertmanagerAlert> alerts = payload.safeAlerts();
    if (EmptyChecks.isEmpty(alerts)) {
      return deliverBatch(tenantId, receiver, channel, payload);
    }
    for (int offset = 0; offset < alerts.size(); ) {
      int end = offset + Math.min(batchSize, alerts.size() - offset);
      List<AlertmanagerAlert> items = alerts.subList(offset, end);
      AlertmanagerWebhookPayload batch = new AlertmanagerWebhookPayload(
          payload.version(),
          payload.groupKey(),
          payload.truncatedAlerts(),
          payload.status(),
          payload.receiver(),
          payload.groupLabels(),
          payload.commonLabels(),
          payload.commonAnnotations(),
          payload.externalURL(),
          items);
      AmNotifyOutcome outcome = deliverBatch(tenantId, receiver, channel, batch);
      if (!outcome.delivered()) {
        return outcome;
      }
      offset = end;
    }
    return new AmNotifyOutcome(receiver, channelCode, true, "SUCCESS", null);
  }

  private AmNotifyOutcome deliverBatch(
      String tenantId,
      String receiver,
      NotificationChannelRow channel,
      AlertmanagerWebhookPayload payload) {
    String channelCode = receiver;
    String channelType = channel.channelType();
    String configJson = channel.configJson();

    RenderedAlertNotification rendered = renderer.render(payload, properties.getMaxAlerts());
    String payloadJson = JsonUtils.toJson(rendered.structured());
    // eventType 用稳定常量 ALERTMANAGER(与 delivery_log 一侧一致):WebhookDispatcher 会把 eventType 写进
    // X-Batch-Event-Type 头,若放含 CR/LF 的异形 alertname/title,JDK http client 抛错 → WEBHOOK 投递静默失败。
    // 人类可读 title 只进 body(rendered.structured() 的 "text" 字段已含),不进 header。
    WebhookEventPayload eventPayload = new WebhookEventPayload(
        tenantId,
        EVENT_TYPE,
        "alertmanager",
        notificationCursor(tenantId, receiver, payload),
        BatchDateTimeSupport.utcNow(),
        rendered.structured());

    WebhookDeliveryResult result =
        deliver(tenantId, channelCode, channelType, configJson, eventPayload, payloadJson);
    String safeErrorSummary = safeFailureText(result.errorSummary());

    writeDeliveryLog(tenantId, channelCode, payloadJson, result, safeErrorSummary);

    if (result.success()) {
      try {
        receiptService.recordDelivered(tenantId, receiver, payload);
      } catch (RuntimeException failure) {
        meterRegistry.counter(METRIC_FAILED, "receiver", receiver).increment();
        log.warn(
            "AM notify receipt persistence failed: receiver={} tenantId={} cause={}",
            LogSanitizer.value(receiver),
            LogSanitizer.value(tenantId),
            safeFailureText(SwallowedExceptionLogger.summary(failure)));
        return new AmNotifyOutcome(
            receiver, channelCode, false, "FAILED", "notification receipt persistence failed");
      }
      log.info(
          "AM notify delivered: receiver={} channel={} alertCount={}",
          LogSanitizer.value(receiver),
          LogSanitizer.value(channelCode),
          payload.safeAlerts().size());
      return new AmNotifyOutcome(receiver, channelCode, true, "SUCCESS", null);
    }
    meterRegistry.counter(METRIC_FAILED, "receiver", receiver).increment();
    log.warn(
        "AM notify delivery failed: receiver={} channel={} error={}",
        LogSanitizer.value(receiver),
        LogSanitizer.value(channelCode),
        safeErrorSummary);
    return new AmNotifyOutcome(receiver, channelCode, false, "FAILED", safeErrorSummary);
  }

  /** 通知重试保持身份；不同实体集合及发生/恢复阶段使用不同身份。 */
  private String notificationCursor(
      String tenantId, String receiver, AlertmanagerWebhookPayload payload) {
    List<String> identities = new ArrayList<>();
    for (AlertmanagerAlert alert : payload.safeAlerts()) {
      String fingerprint = alert.label("fingerprint");
      if (EmptyChecks.isNull(fingerprint) || EmptyChecks.isBlank(fingerprint)) {
        fingerprint = alert.fingerprint();
      }
      NotificationIdentity identity =
          new NotificationIdentity(fingerprint, alert.status(), alert.startsAt());
      String encodedIdentity = JsonUtils.toJson(identity);
      identities.add(encodedIdentity);
    }
    identities.sort(String::compareTo);
    String identityJson = JsonUtils.toJson(identities);
    String resource = receiver + ":" + payload.groupKey() + ":" + identityJson;
    return AlertFingerprints.build(tenantId, EVENT_TYPE, resource);
  }

  private record NotificationIdentity(String fingerprint, String status, String startsAt) {}

  /**
   * 按 AM payload 的 {@code tenant} label 反查租户(迁移方案 §4/§7 硬前置):AM 单实例全局,租户维度只能靠 label 携带。
   *
   * <p>优先取 {@code commonLabels.tenant}(整批共享的租户);缺失时回退到首条 alert 的 {@code labels.tenant}; 再缺失才回退到配置的
   * {@link AlertmanagerNotifyProperties#getTenantId()} fallback(默认 {@code system})。 不做反查会让所有租户的告警全落
   * system 租户的渠道。
   */
  private String resolveTenant(AlertmanagerWebhookPayload payload) {
    if (payload != null) {
      Map<String, String> common = payload.commonLabels();
      if (common != null && hasText(common.get(LABEL_TENANT))) {
        return common.get(LABEL_TENANT).trim();
      }
      for (AlertmanagerAlert alert : payload.safeAlerts()) {
        String tenant = alert == null ? null : alert.label(LABEL_TENANT);
        if (hasText(tenant)) {
          return tenant.trim();
        }
      }
    }
    return properties.getTenantId();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private WebhookDeliveryResult deliver(
      String tenantId,
      String channelCode,
      String channelType,
      String configJson,
      WebhookEventPayload payload,
      String payloadJson) {
    WebhookDeliverySupport.DeliveryContext deliveryContext =
        new WebhookDeliverySupport.DeliveryContext(
            tenantId,
            channelCode,
            channelType,
            configJson,
            payload,
            payloadJson,
            senderRegistry,
            webhookDispatcher,
            AlertmanagerNotifyService.class);
    return WebhookDeliverySupport.deliver(deliveryContext);
  }

  private void writeDeliveryLog(
      String tenantId,
      String channelCode,
      String payloadJson,
      WebhookDeliveryResult result,
      String safeErrorSummary) {
    try {
      NotificationDeliveryLogInsertParam row = NotificationDeliveryLogInsertParam.builder()
          .tenantId(tenantId)
          .ruleId(0L)
          .channelCode(channelCode)
          .eventType(EVENT_TYPE)
          .alertEventId(null)
          .payloadJson(payloadJson)
          .deliveryStatus(result.success() ? "SUCCESS" : "FAILED")
          .errorMessage(safeErrorSummary)
          .attempt(1)
          .build();
      deliveryLogMapper.insert(row);
    } catch (RuntimeException ex) {
      // 日志写入是 off 关键路径的审计动作,失败只 warn,不影响回执 AM。
      log.warn(
          "AM notify delivery log persist failed: channel={} cause={}",
          LogSanitizer.value(channelCode),
          safeFailureText(SwallowedExceptionLogger.summary(ex)));
    }
  }

  private static String safeFailureText(String value) {
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    String normalized = ConsoleTextSanitizer.normalize(HtmlUtils.htmlUnescape(value));
    return SwallowedExceptionLogger.safeMessage(normalized, 1024);
  }
}
