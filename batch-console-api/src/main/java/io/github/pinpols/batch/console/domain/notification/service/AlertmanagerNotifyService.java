package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.config.AlertmanagerNotifyProperties;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationChannelMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.web.request.AlertmanagerWebhookPayload;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
  private static final String COL_CHANNEL_TYPE = "channel_type";
  private static final String COL_CONFIG_JSON = "config_json";
  private static final String EVENT_TYPE = "ALERTMANAGER";

  private final AlertmanagerNotifyProperties properties;
  private final NotificationChannelMapper channelMapper;
  private final NotificationDeliveryLogMapper deliveryLogMapper;
  private final NotificationSenderRegistry senderRegistry;
  private final WebhookDispatcher webhookDispatcher;
  private final AlertmanagerAlertRenderer renderer;

  /** 投递结果(供接收端回执;delivered=false 且 status=SKIPPED 表示无对应渠道)。 */
  public record AmNotifyOutcome(
      String receiver, String channelCode, boolean delivered, String status, String detail) {}

  /** 接住一个 receiver 的 AM 告警并投递。绝不抛异常(off 关键路径),失败折叠进 outcome + 日志。 */
  public AmNotifyOutcome deliver(String receiver, AlertmanagerWebhookPayload payload) {
    String tenantId = properties.getTenantId();
    String channelCode = receiver;
    Map<String, Object> channel = channelMapper.selectByCode(tenantId, channelCode);
    if (channel == null) {
      log.warn(
          "AM notify skipped: no notification_channel for receiver={} tenantId={}",
          receiver,
          tenantId);
      return new AmNotifyOutcome(
          receiver, channelCode, false, "SKIPPED", "no channel configured for receiver");
    }

    String channelType = str(channel, COL_CHANNEL_TYPE);
    String configJson = str(channel, COL_CONFIG_JSON);

    RenderedAlertNotification rendered = renderer.render(payload, properties.getMaxAlerts());
    String payloadJson = JsonUtils.toJson(rendered.structured());
    WebhookEventPayload eventPayload =
        new WebhookEventPayload(
            tenantId,
            rendered.title(),
            "alertmanager",
            payload.groupKey(),
            BatchDateTimeSupport.utcNow(),
            rendered.structured());

    WebhookDeliveryResult result =
        deliver(tenantId, channelCode, channelType, configJson, eventPayload, payloadJson);

    writeDeliveryLog(tenantId, channelCode, payloadJson, result);

    if (result.success()) {
      log.info(
          "AM notify delivered: receiver={} channel={} alertCount={}",
          receiver,
          channelCode,
          payload.safeAlerts().size());
      return new AmNotifyOutcome(receiver, channelCode, true, "SUCCESS", null);
    }
    log.warn(
        "AM notify delivery failed: receiver={} channel={} error={}",
        receiver,
        channelCode,
        result.errorSummary());
    return new AmNotifyOutcome(receiver, channelCode, false, "FAILED", result.errorSummary());
  }

  private WebhookDeliveryResult deliver(
      String tenantId,
      String channelCode,
      String channelType,
      String configJson,
      WebhookEventPayload payload,
      String payloadJson) {
    if (channelType == null || channelType.isBlank()) {
      return WebhookDeliveryResult.failure(null, "channel has no channel_type");
    }
    if (CHANNEL_TYPE_WEBHOOK.equalsIgnoreCase(channelType)) {
      WebhookSubscriptionEntity synthetic =
          toSyntheticWebhookSubscription(tenantId, channelCode, configJson);
      if (synthetic == null) {
        return WebhookDeliveryResult.failure(null, "webhook channel missing config url");
      }
      return webhookDispatcher.attemptDelivery(synthetic, payload, payloadJson);
    }
    NotificationSender sender = senderRegistry.resolve(channelType);
    if (sender == null) {
      return WebhookDeliveryResult.failure(
          null, "no sender registered for channel type: " + channelType);
    }
    return sender.send(
        new NotificationMessage(
            tenantId, channelCode, channelType, configJson, payload, payloadJson));
  }

  private void writeDeliveryLog(
      String tenantId, String channelCode, String payloadJson, WebhookDeliveryResult result) {
    try {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("tenantId", tenantId);
      row.put("ruleId", 0);
      row.put("channelCode", channelCode);
      row.put("eventType", EVENT_TYPE);
      row.put("alertEventId", null);
      row.put("payloadJson", payloadJson);
      row.put("deliveryStatus", result.success() ? "SUCCESS" : "FAILED");
      row.put("errorMessage", result.errorSummary());
      row.put("attempt", 1);
      deliveryLogMapper.insert(row);
    } catch (RuntimeException ex) {
      // 日志写入是 off 关键路径的审计动作,失败只 warn,不影响回执 AM。
      log.warn("AM notify delivery log persist failed: channel={}", channelCode, ex);
    }
  }

  private WebhookSubscriptionEntity toSyntheticWebhookSubscription(
      String tenantId, String channelCode, String configJson) {
    Map<String, Object> config = parseConfig(configJson);
    String url = str(config, "url");
    if (url == null || url.isBlank()) {
      return null;
    }
    WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity();
    entity.setTenantId(tenantId);
    entity.setName(channelCode);
    entity.setCallbackUrl(url);
    entity.setSecret(str(config, "secret"));
    entity.setEnabled(Boolean.TRUE);
    return entity;
  }

  private Map<String, Object> parseConfig(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, Object> parsed =
          JsonUtils.fromJson(
              configJson,
              new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
      return parsed == null ? Map.of() : parsed;
    } catch (RuntimeException ex) {
      log.info("AM notify config_json parse failed", ex);
      return Map.of();
    }
  }

  private static String str(Map<String, Object> map, String key) {
    Object value = map == null ? null : map.get(key);
    return value == null ? null : value.toString();
  }
}
