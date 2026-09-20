package io.github.pinpols.batch.console.domain.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.SmsProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 短信通知发送器(channelType=SMS),{@link NotificationSender} 唯一 SMS 实现。 解析 config_json 的 {@code
 * phoneNumbers}(逗号分隔手机号),按 {@code batch.console.sms.provider} 委托给对应 {@link SmsProvider}
 * (aliyun/tencent/twilio)。未配 provider 或无对应实现 → 显式 failure(不静默)。
 *
 * <p>限流/去重在 {@link SubscriptionRuleWebhookDispatcher} 分发层已统一拦截;本类只管"解析 + 委托"。日志净化:不打手机号明文。
 */
@Slf4j
@Component
public class SmsNotificationSender implements NotificationSender {

  private final Map<String, SmsProvider> providersByCode;
  private final SmsProperties properties;
  private final ObjectMapper objectMapper;

  public SmsNotificationSender(
      List<SmsProvider> providers, SmsProperties properties, ObjectMapper objectMapper) {
    this.providersByCode = indexProviders(providers);
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @Override
  public String channelType() {
    return "SMS";
  }

  @Override
  public WebhookDeliveryResult send(NotificationMessage message) {
    List<String> phoneNumbers = parsePhoneNumbers(message.configJson());
    if (phoneNumbers.isEmpty()) {
      return WebhookDeliveryResult.failure(null, "missing sms phoneNumbers");
    }
    String providerName = properties.getProvider();
    if (EmptyChecks.isBlank(providerName) || "none".equalsIgnoreCase(providerName)) {
      return WebhookDeliveryResult.failure(null, "sms provider not configured");
    }
    SmsProvider provider = resolve(providerName);
    if (EmptyChecks.isNull(provider)) {
      log.warn(
          "SMS channel selected but no provider impl for '{}'; skipping: channelCode={}",
          providerName,
          message.channelCode());
      return WebhookDeliveryResult.failure(null, "sms provider not available: " + providerName);
    }
    return provider.send(phoneNumbers, message);
  }

  private SmsProvider resolve(String providerName) {
    return providersByCode.get(providerName.trim().toLowerCase(Locale.ROOT));
  }

  private static Map<String, SmsProvider> indexProviders(List<SmsProvider> providers) {
    Map<String, SmsProvider> registered = new LinkedHashMap<>();
    for (SmsProvider provider : providers) {
      String code = provider.providerCode();
      if (EmptyChecks.isBlank(code)) {
        throw new IllegalStateException(
            "SmsProvider providerCode must not be blank: " + provider.getClass().getName());
      }
      String normalized = code.trim().toLowerCase(Locale.ROOT);
      SmsProvider duplicate = registered.putIfAbsent(normalized, provider);
      if (EmptyChecks.isNotNull(duplicate)) {
        throw new IllegalStateException("duplicate SmsProvider providerCode: " + normalized);
      }
    }
    return Map.copyOf(registered);
  }

  private List<String> parsePhoneNumbers(String configJson) {
    List<String> result = new ArrayList<>();
    if (EmptyChecks.isBlank(configJson)) {
      return result;
    }
    try {
      JsonNode node = objectMapper.readTree(configJson).get("phoneNumbers");
      if (EmptyChecks.isNull(node) || node.isNull()) {
        return result;
      }
      for (String raw : node.asText().split(",")) {
        String trimmed = raw.trim();
        if (!trimmed.isBlank()) {
          result.add(trimmed);
        }
      }
    } catch (Exception ex) {
      log.warn("SMS config_json parse failed: cause={}", ex.getClass().getSimpleName());
    }
    return result;
  }
}
