package com.example.batch.console.infrastructure.realtime;

import com.example.batch.console.service.WebhookDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 将控制台实时领域事件桥接到 webhook 分发器。
 */
@Component
@RequiredArgsConstructor
public class ConsoleWebhookDomainEventListener {

    private final WebhookDispatcher webhookDispatcher;

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
                event.emittedAt()
        );
    }
}
