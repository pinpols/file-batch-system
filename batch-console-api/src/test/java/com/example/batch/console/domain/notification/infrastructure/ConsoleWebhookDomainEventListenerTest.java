package com.example.batch.console.domain.notification.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.notification.service.WebhookDispatcher;
import com.example.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEvent;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsoleWebhookDomainEventListenerTest {

  private WebhookDispatcher webhookDispatcher;
  private ConsoleWebhookDomainEventListener listener;

  @BeforeEach
  void setUp() {
    webhookDispatcher = mock(WebhookDispatcher.class);
    listener = new ConsoleWebhookDomainEventListener(webhookDispatcher);
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
  }

  @Test
  void shouldSkipNullEvent() {
    listener.onDomainEvent(null);

    verifyNoInteractions(webhookDispatcher);
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

    verifyNoInteractions(webhookDispatcher);
  }
}
