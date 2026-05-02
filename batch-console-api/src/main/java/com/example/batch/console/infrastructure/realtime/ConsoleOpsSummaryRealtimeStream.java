package com.example.batch.console.infrastructure.realtime;

import com.example.batch.console.application.ConsoleOpsApplicationService;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.web.response.ops.ConsoleOpsSummaryResponse;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 控制台运维摘要实时流。
 *
 * <p>负责订阅首屏摘要，并在关键写操作后推送最新快照。
 */
@Service
@RequiredArgsConstructor
public class ConsoleOpsSummaryRealtimeStream {

  private static final String STREAM = "ops-summary";
  private static final String EVENT_TYPE = "ops-summary-updated";
  private static final long REFRESH_DEBOUNCE_MILLIS = 300L;
  private static final long SUMMARY_CACHE_TTL_MILLIS = 1_000L;

  private final ConsoleOpsApplicationService opsApplicationService;
  private final ConsoleRealtimeEventHub realtimeEventHub;
  private final ConsoleRealtimeRedisPublisher redisPublisher;
  private final ConsoleRealtimeCursorFactory cursorFactory;
  private final ConsoleTenantGuard tenantGuard;
  private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledRefreshes =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, CachedSummary> summaryCache = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(new SummaryThreadFactory());

  public void publishRefresh(String tenantId) {
    publishRefresh(tenantId, false);
  }

  private void publishRefresh(String tenantId, boolean force) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    Runnable publish =
        () -> {
          if (force) {
            publishSummarySnapshot(resolvedTenantId, true);
            return;
          }
          scheduleCoalescedRefresh(resolvedTenantId);
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              publish.run();
            }
          });
      return;
    }
    publish.run();
  }

  public SseEmitter subscribe(String tenantId, Long heartbeatMillis) {
    return subscribe(tenantId, heartbeatMillis, true);
  }

  public SseEmitter subscribe(String tenantId, Long heartbeatMillis, boolean initialSnapshot) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    SseEmitter emitter =
        realtimeEventHub.subscribe(resolvedTenantId, STREAM, null, null, heartbeatMillis);
    if (initialSnapshot) {
      publishRefresh(resolvedTenantId, true);
    }
    return emitter;
  }

  @PreDestroy
  void shutdown() {
    for (ScheduledFuture<?> future : scheduledRefreshes.values()) {
      if (future != null) {
        future.cancel(true);
      }
    }
    scheduler.shutdownNow();
  }

  private void scheduleCoalescedRefresh(String tenantId) {
    scheduledRefreshes.compute(
        tenantId,
        (key, existing) -> {
          if (existing != null && !existing.isDone()) {
            return existing;
          }
          return scheduler.schedule(
              () -> {
                try {
                  publishSummarySnapshot(tenantId, true);
                } finally {
                  scheduledRefreshes.remove(tenantId);
                }
              },
              REFRESH_DEBOUNCE_MILLIS,
              TimeUnit.MILLISECONDS);
        });
  }

  private void publishSummarySnapshot(String tenantId, boolean forceRefresh) {
    ConsoleOpsSummaryResponse summary = loadSummary(tenantId, forceRefresh);
    if (summary == null) {
      return;
    }
    ConsoleSseEvent event =
        new ConsoleSseEvent(
            tenantId, STREAM, EVENT_TYPE, cursorFactory.nextCursor(), summary, Instant.now());
    realtimeEventHub.publish(event);
    redisPublisher.publish(event);
  }

  void publishSnapshot(String tenantId) {
    publishSummarySnapshot(tenantId, true);
  }

  private ConsoleOpsSummaryResponse loadSummary(String tenantId, boolean forceRefresh) {
    long now = System.currentTimeMillis();
    CachedSummary cached = summaryCache.get(tenantId);
    if (!forceRefresh
        && cached != null
        && now - cached.cachedAtMillis() <= SUMMARY_CACHE_TTL_MILLIS) {
      return cached.summary();
    }
    ConsoleOpsSummaryResponse summary = opsApplicationService.summary(tenantId);
    summaryCache.put(tenantId, new CachedSummary(summary, now));
    return summary;
  }

  private record CachedSummary(ConsoleOpsSummaryResponse summary, long cachedAtMillis) {}

  private static final class SummaryThreadFactory implements ThreadFactory {
    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "console-ops-summary-realtime");
      thread.setDaemon(true);
      return thread;
    }
  }
}
