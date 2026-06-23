package io.github.pinpols.batch.console.domain.notification.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.notification.service.SubscriptionRuleWebhookDispatcher;
import io.github.pinpols.batch.console.domain.notification.service.WebhookDispatcher;
import io.github.pinpols.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEvent;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsoleWebhookDomainEventListenerTest {

  private WebhookDispatcher webhookDispatcher;
  private SubscriptionRuleWebhookDispatcher subscriptionRuleWebhookDispatcher;
  private ConsoleWebhookDomainEventListener listener;

  @BeforeEach
  void setUp() {
    webhookDispatcher = mock(WebhookDispatcher.class);
    subscriptionRuleWebhookDispatcher = mock(SubscriptionRuleWebhookDispatcher.class);
    listener =
        new ConsoleWebhookDomainEventListener(webhookDispatcher, subscriptionRuleWebhookDispatcher);
  }

  @Test
  void shouldDispatchEvent() {
    Instant now = BatchDateTimeSupport.utcNow();
    ConsoleRealtimeDomainEvent event =
        ConsoleRealtimeDomainEvent.builder().tenantId("tenant1").stream("job-instance")
            .eventType("JOB_COMPLETED")
            .cursor("cursor-1")
            .data("payload")
            .emittedAt(now)
            .build();

    listener.onDomainEvent(event);

    verify(webhookDispatcher)
        .dispatchAsync("tenant1", "JOB_COMPLETED", "job-instance", "cursor-1", "payload", now);
    verify(subscriptionRuleWebhookDispatcher)
        .dispatch("tenant1", "JOB_COMPLETED", "job-instance", "cursor-1", "payload", now);
  }

  @Test
  void shouldSkipNullEvent() {
    listener.onDomainEvent(null);

    verifyNoInteractions(webhookDispatcher, subscriptionRuleWebhookDispatcher);
  }

  @Test
  void shouldSkipEventWithBlankTenantId() {
    ConsoleRealtimeDomainEvent event =
        ConsoleRealtimeDomainEvent.builder().tenantId("  ").stream("job-instance")
            .eventType("JOB_COMPLETED")
            .cursor("cursor-1")
            .data("payload")
            .emittedAt(BatchDateTimeSupport.utcNow())
            .build();

    listener.onDomainEvent(event);

    verifyNoInteractions(webhookDispatcher, subscriptionRuleWebhookDispatcher);
  }
}
