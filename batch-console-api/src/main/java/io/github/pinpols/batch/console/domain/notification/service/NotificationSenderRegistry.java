package io.github.pinpols.batch.console.domain.notification.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 按 channelType 解析 {@link NotificationSender} 的注册表。构造期校验渠道键非空且唯一，避免多个实现支持同一渠道时由 Spring Bean
 * 顺序隐式决定路由结果；无匹配返回 null（上层显式告警跳过，绝不静默丢弃）。
 */
@Component
public class NotificationSenderRegistry {

  private final Map<String, NotificationSender> sendersByType;

  public NotificationSenderRegistry(List<NotificationSender> senders) {
    Map<String, NotificationSender> registered = new LinkedHashMap<>();
    for (NotificationSender sender : senders) {
      String channelType = normalize(sender.channelType());
      if (channelType == null) {
        throw new IllegalStateException("NotificationSender channelType must not be blank: "
            + sender.getClass().getName());
      }
      NotificationSender duplicate = registered.putIfAbsent(channelType, sender);
      if (duplicate != null) {
        throw new IllegalStateException("duplicate NotificationSender channelType: " + channelType);
      }
    }
    this.sendersByType = Map.copyOf(registered);
  }

  /** 解析能处理 channelType 的 sender；无则 null。 */
  public NotificationSender resolve(String channelType) {
    String normalized = normalize(channelType);
    return normalized == null ? null : sendersByType.get(normalized);
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
  }
}
