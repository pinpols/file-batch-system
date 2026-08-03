package io.github.pinpols.batch.console.domain.observability.realtime;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.shared.event.ConsoleRealtimeDomainEvent;
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
public class ConsoleRealtimeDomainEventPublisher implements ConsoleRealtimeEventPort {

  private final ApplicationEventPublisher applicationEventPublisher;
  private final ConsoleRealtimeCursorFactory cursorFactory;

  @Override
  public void publishChanged(String tenantId, String stream, String eventType) {
    publish(tenantId, stream, eventType, null, false);
  }

  @Override
  public void publishChanged(String tenantId, String stream, String eventType, Object data) {
    publish(tenantId, stream, eventType, data, false);
  }

  @Override
  public void publishSummaryRefresh(String tenantId) {
    publish(tenantId, "ops-summary", "ops-summary-refresh-requested", null, true);
  }

  private void publish(
      String tenantId, String stream, String eventType, Object data, boolean summaryRefresh) {
    // 这里只发布应用内事件，真正的 SSE 分发由 bridge 在事务提交后统一处理。
    ConsoleRealtimeDomainEvent event =
        ConsoleRealtimeDomainEvent.builder().tenantId(tenantId).stream(stream)
            .eventType(eventType)
            .cursor(cursorFactory.nextCursor())
            .data(data)
            .summaryRefresh(summaryRefresh)
            .emittedAt(BatchDateTimeSupport.utcNow())
            .build();
    applicationEventPublisher.publishEvent(event);
  }
}
