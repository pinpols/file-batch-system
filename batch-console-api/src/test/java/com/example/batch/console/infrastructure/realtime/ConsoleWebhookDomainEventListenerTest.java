package com.example.batch.console.infrastructure.realtime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.batch.console.service.WebhookDispatcher;
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
    Instant now = Instant.now();
    ConsoleRealtimeDomainEvent event =
        new ConsoleRealtimeDomainEvent(
            "tenant1", "job-instance", "JOB_COMPLETED", "cursor-1", "payload", false, now);

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
        new ConsoleRealtimeDomainEvent(
            "  ", "job-instance", "JOB_COMPLETED", "cursor-1", "payload", false, Instant.now());

    listener.onDomainEvent(event);

    verifyNoInteractions(webhookDispatcher);
  }
}
