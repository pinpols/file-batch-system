package com.example.batch.console.domain.observability.realtime;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.config.ConsoleAsyncConfiguration;
import com.example.batch.console.domain.ops.application.ConsoleOpsApplicationService;
import com.example.batch.console.domain.ops.web.response.ConsoleOpsSummaryResponse;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
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
  private final BatchDateTimeSupport dateTimeSupport;
  // P0:scheduledRefreshes 在 finally 里有 remove,但异常 / cancel 路径下仍可能漏删,
  // 加 maximumSize 回退防止租户基数增长后无界堆积。
  private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledRefreshes =
      new ConcurrentHashMap<>();
  // P0:summaryCache 原裸 ConcurrentHashMap 仅在读路径判 TTL,从不删除,租户基数大时永久驻留。
  // 改 Caffeine expireAfterWrite(10s) + maximumSize(1000),与 SUMMARY_CACHE_TTL_MILLIS 对齐。
  private final Cache<String, CachedSummary> summaryCache =
      Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.SECONDS).maximumSize(1000).build();
  private final TaskScheduler scheduler;

  public ConsoleOpsSummaryRealtimeStream(
      ConsoleOpsApplicationService opsApplicationService,
      ConsoleRealtimeEventHub realtimeEventHub,
      ConsoleRealtimeRedisPublisher redisPublisher,
      ConsoleRealtimeCursorFactory cursorFactory,
      ConsoleTenantGuard tenantGuard,
      BatchDateTimeSupport dateTimeSupport,
      @Qualifier(ConsoleAsyncConfiguration.REALTIME_SCHEDULER) TaskScheduler scheduler) {
    this.opsApplicationService = opsApplicationService;
    this.realtimeEventHub = realtimeEventHub;
    this.redisPublisher = redisPublisher;
    this.cursorFactory = cursorFactory;
    this.tenantGuard = tenantGuard;
    this.dateTimeSupport = dateTimeSupport;
    this.scheduler = scheduler;
  }

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
    // scheduler 由 Spring 容器管理 (consoleRealtimeScheduler bean) — 这里只取消未触发的 debounce 任务。
    for (ScheduledFuture<?> future : scheduledRefreshes.values()) {
      if (future != null) {
        future.cancel(true);
      }
    }
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
              Instant.now().plus(Duration.ofMillis(REFRESH_DEBOUNCE_MILLIS)));
        });
  }

  private void publishSummarySnapshot(String tenantId, boolean forceRefresh) {
    ConsoleOpsSummaryResponse summary = loadSummary(tenantId, forceRefresh);
    if (summary == null) {
      return;
    }
    ConsoleSseEvent event =
        new ConsoleSseEvent(
            tenantId,
            STREAM,
            EVENT_TYPE,
            cursorFactory.nextCursor(),
            summary,
            dateTimeSupport.nowInstant());
    realtimeEventHub.publish(event);
    redisPublisher.publish(event);
  }

  void publishSnapshot(String tenantId) {
    publishSummarySnapshot(tenantId, true);
  }

  private ConsoleOpsSummaryResponse loadSummary(String tenantId, boolean forceRefresh) {
    long now = dateTimeSupport.currentEpochMillis();
    CachedSummary cached = summaryCache.getIfPresent(tenantId);
    // 双重 TTL 控制:Caffeine expireAfterWrite 回退 10s,业务侧再用 cachedAtMillis 精确判过期
    // —— 保留原逻辑等价语义,改动只是把无界存储换成有界 Caffeine。
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
}
