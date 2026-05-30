package com.example.batch.console.domain.observability.realtime;

import com.example.batch.console.config.ConsoleAsyncConfiguration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 控制台实时事件桥接器。
 *
 * <p>它把 service 层发布的领域事件转换成 SSE 推送或摘要刷新。
 */
@Service
public class ConsoleRealtimeEventBridge {

  private final ConsoleRealtimeEventHub realtimeEventHub;
  private final ConsoleRealtimeRedisPublisher redisPublisher;
  private final ConsoleOpsSummaryRealtimeStream summaryRealtimeStream;

  public ConsoleRealtimeEventBridge(
      ConsoleRealtimeEventHub realtimeEventHub,
      ConsoleRealtimeRedisPublisher redisPublisher,
      ConsoleOpsSummaryRealtimeStream summaryRealtimeStream) {
    this.realtimeEventHub = realtimeEventHub;
    this.redisPublisher = redisPublisher;
    this.summaryRealtimeStream = summaryRealtimeStream;
  }

  // P0:原同步执行 + fallbackExecution=true 在无事务路径上会占用 Tomcat 工作线程做
  // Redis Pub/Sub 同步 IO,Redis 抖动时阻塞业务请求线程。挂 @Async 走有界 pushTaskExecutor
  // (core=4 / max=16 / queue=200 / CallerRunsPolicy)异步化,保留 fallbackExecution
  // 以便在测试 / 无事务上下文中仍能触发。
  @Async(ConsoleAsyncConfiguration.PUSH_TASK_EXECUTOR)
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onDomainEvent(ConsoleRealtimeDomainEvent event) {
    if (event == null) {
      return;
    }
    // ops-summary 需要重新拉取最新摘要快照，不能直接透传一个空变更事件给前端。
    if (event.summaryRefresh()) {
      summaryRealtimeStream.publishRefresh(event.tenantId());
      return;
    }
    // 其余 domain event 在这里先直发本机 SSE，再同步写入 Redis Pub/Sub。
    ConsoleSseEvent sseEvent =
        new ConsoleSseEvent(
            event.tenantId(),
            event.stream(),
            event.eventType(),
            event.cursor(),
            event.data(),
            event.emittedAt());
    realtimeEventHub.publish(sseEvent);
    redisPublisher.publish(sseEvent);
  }
}
