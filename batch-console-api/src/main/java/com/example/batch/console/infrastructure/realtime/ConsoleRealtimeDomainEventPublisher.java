package com.example.batch.console.infrastructure.realtime;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 控制台实时领域事件发布器。
 *
 * <p>它只负责发 Spring 应用事件，不直接碰 SSE。
 */
@Service
@RequiredArgsConstructor
public class ConsoleRealtimeDomainEventPublisher {

  private final ApplicationEventPublisher applicationEventPublisher;
  private final ConsoleRealtimeCursorFactory cursorFactory;

  public void publishChanged(String tenantId, String stream, String eventType) {
    publish(tenantId, stream, eventType, null, false);
  }

  public void publishChanged(String tenantId, String stream, String eventType, Object data) {
    publish(tenantId, stream, eventType, data, false);
  }

  public void publishSummaryRefresh(String tenantId) {
    publish(tenantId, "ops-summary", "ops-summary-refresh-requested", null, true);
  }

  private void publish(
      String tenantId, String stream, String eventType, Object data, boolean summaryRefresh) {
    // 这里只发布应用内事件，真正的 SSE 分发由 bridge 在事务提交后统一处理。
    applicationEventPublisher.publishEvent(
        new ConsoleRealtimeDomainEvent(
            tenantId,
            stream,
            eventType,
            cursorFactory.nextCursor(),
            data,
            summaryRefresh,
            Instant.now()));
  }
}
