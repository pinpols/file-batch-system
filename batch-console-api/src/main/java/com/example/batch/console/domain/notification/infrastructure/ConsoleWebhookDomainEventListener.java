package com.example.batch.console.domain.notification.infrastructure;

import com.example.batch.console.domain.notification.service.SubscriptionRuleWebhookDispatcher;
import com.example.batch.console.domain.notification.service.WebhookDispatcher;
import com.example.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 将控制台实时领域事件桥接到 webhook 分发器。
 *
 * <p>两路并存,互不影响:
 *
 * <ul>
 *   <li>旧路 {@link WebhookDispatcher}:读 {@code webhook_subscription} 表。
 *   <li>P1-2 新路 {@link SubscriptionRuleWebhookDispatcher}:读前端通知中心配置的 {@code subscription_rule} +
 *       {@code notification_channel}(WEBHOOK 类型)。在此之前后者配了规则却永不分发(死链路)。
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ConsoleWebhookDomainEventListener {

  private final WebhookDispatcher webhookDispatcher;
  private final SubscriptionRuleWebhookDispatcher subscriptionRuleWebhookDispatcher;

  @EventListener
  public void onDomainEvent(ConsoleRealtimeDomainEvent event) {
    if (event == null || event.tenantId() == null || event.tenantId().isBlank()) {
      return;
    }
    webhookDispatcher.dispatchAsync(
        event.tenantId(),
        event.eventType(),
        event.stream(),
        event.cursor(),
        event.data(),
        event.emittedAt());
    subscriptionRuleWebhookDispatcher.dispatch(
        event.tenantId(),
        event.eventType(),
        event.stream(),
        event.cursor(),
        event.data(),
        event.emittedAt());
  }
}
