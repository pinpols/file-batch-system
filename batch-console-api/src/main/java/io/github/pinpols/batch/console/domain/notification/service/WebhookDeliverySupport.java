package io.github.pinpols.batch.console.domain.notification.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import java.util.Map;

/** Shared channel delivery and synthetic webhook mapping used by notification entry points. */
public final class WebhookDeliverySupport {

  private static final String CHANNEL_TYPE_WEBHOOK = "WEBHOOK";

  private WebhookDeliverySupport() {}

  public record DeliveryContext(
      String tenantId,
      String channelCode,
      String channelType,
      String configJson,
      WebhookEventPayload payload,
      String payloadJson,
      NotificationSenderRegistry senderRegistry,
      WebhookDispatcher webhookDispatcher,
      Class<?> logOwner) {}

  public static WebhookDeliveryResult deliver(DeliveryContext context) {
    String tenantId = context.tenantId();
    String channelCode = context.channelCode();
    String channelType = context.channelType();
    String configJson = context.configJson();
    if (channelType == null || channelType.isBlank()) {
      return WebhookDeliveryResult.failure(null, "channel has no channel_type");
    }
    if (CHANNEL_TYPE_WEBHOOK.equalsIgnoreCase(channelType)) {
      WebhookSubscriptionEntity subscription =
          syntheticSubscription(tenantId, channelCode, configJson, context.logOwner());
      if (subscription == null) {
        return WebhookDeliveryResult.failure(null, "webhook channel missing config url");
      }
      return context
          .webhookDispatcher()
          .attemptDelivery(subscription, context.payload(), context.payloadJson());
    }
    NotificationSender sender = context.senderRegistry().resolve(channelType);
    if (sender == null) {
      return WebhookDeliveryResult.failure(
          null, "no sender registered for channel type: " + channelType);
    }
    return sender.send(new NotificationMessage(
        tenantId, channelCode, channelType, configJson, context.payload(), context.payloadJson()));
  }

  private static WebhookSubscriptionEntity syntheticSubscription(
      String tenantId, String channelCode, String configJson, Class<?> logOwner) {
    Map<String, Object> config = parseConfig(configJson, logOwner);
    String url = text(config, "url");
    if (url == null || url.isBlank()) {
      return null;
    }
    WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity();
    entity.setTenantId(tenantId);
    entity.setName(channelCode);
    entity.setCallbackUrl(url);
    entity.setSecret(text(config, "secret"));
    entity.setEnabled(Boolean.TRUE);
    return entity;
  }

  private static Map<String, Object> parseConfig(String configJson, Class<?> logOwner) {
    if (configJson == null || configJson.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, Object> parsed =
          JsonUtils.fromJson(configJson, new TypeReference<Map<String, Object>>() {});
      return parsed == null ? Map.of() : parsed;
    } catch (RuntimeException ex) {
      SwallowedExceptionLogger.info(logOwner, "catch:config_json parse", ex);
      return Map.of();
    }
  }

  private static String text(Map<String, Object> config, String key) {
    Object value = config.get(key);
    return value == null ? null : value.toString();
  }

  public static String configText(String configJson, String key, Class<?> logOwner) {
    return text(parseConfig(configJson, logOwner), key);
  }
}
