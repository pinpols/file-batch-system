package io.github.pinpols.batch.console.domain.notification.service;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 按 channelType 解析 {@link NotificationSender} 的注册表。注入容器内全部 sender 实现， 首个 {@code
 * supports(type)==true} 者胜出；无匹配返回 null（上层显式告警跳过，绝不静默丢弃）。
 */
@Component
public class NotificationSenderRegistry {

  private final List<NotificationSender> senders;

  public NotificationSenderRegistry(List<NotificationSender> senders) {
    this.senders = List.copyOf(senders);
  }

  /** 解析能处理 channelType 的 sender；无则 null。 */
  public NotificationSender resolve(String channelType) {
    if (channelType == null || channelType.isBlank()) {
      return null;
    }
    for (NotificationSender sender : senders) {
      if (sender.supports(channelType)) {
        return sender;
      }
    }
    return null;
  }
}
