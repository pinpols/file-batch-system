package io.github.pinpols.batch.console.domain.notification.infrastructure;

import io.github.pinpols.batch.console.config.ConsoleAsyncConfiguration;
import io.github.pinpols.batch.console.domain.notification.service.SubscriptionRuleWebhookDispatcher;
import io.github.pinpols.batch.console.domain.notification.service.WebhookDispatcher;
import io.github.pinpols.batch.console.shared.event.ConsoleRealtimeDomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

  // 外部通知只能观察到已提交事实。异步执行同时避免查询订阅、落投递记录占用原请求线程。
  @Async(ConsoleAsyncConfiguration.PUSH_TASK_EXECUTOR)
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
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
