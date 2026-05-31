package com.example.batch.sdk.scheduler;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.internal.PlatformHttpClient;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * 任务 lease 续约 — 按 {@link BatchPlatformClientConfig#getLeaseRenewInterval()} 周期遍历 dispatcher 的
 * {@link TaskDispatcher#inFlightTaskIds() in-flight set},对每个 taskId 调 {@code
 * /api/internal/tasks/{id}/renew-lease}。
 *
 * <p>单 task 续约失败(404 / 已被回收)→ 记 warn,不抛(下轮再试,或自然走完 dispatcher 流程)。整体 tick 异常被吞,scheduler 不死。
 */
@Slf4j
public class LeaseRenewalScheduler implements AutoCloseable {

  private final BatchPlatformClientConfig config;
  private final PlatformHttpClient httpClient;
  private final TaskDispatcher dispatcher;
  private final ScheduledExecutorService scheduler;

  public LeaseRenewalScheduler(
      BatchPlatformClientConfig config, PlatformHttpClient httpClient, TaskDispatcher dispatcher) {
    this.config = config;
    this.httpClient = httpClient;
    this.dispatcher = dispatcher;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "batch-sdk-lease-renewal");
              t.setDaemon(true);
              return t;
            });
  }

  public void start() {
    long intervalMs = config.getLeaseRenewInterval().toMillis();
    scheduler.scheduleAtFixedRate(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    log.info("LeaseRenewalScheduler started: interval={}ms", intervalMs);
  }

  void tick() {
    try {
      Set<Long> ids = dispatcher.inFlightTaskIds();
      if (ids.isEmpty()) return;
      for (Long taskId : ids) {
        try {
          httpClient.renewLease(taskId, Map.of("workerCode", config.getWorkerCode()));
        } catch (Throwable t) {
          log.warn("renew-lease failed for taskId={}: {}", taskId, t.getMessage());
        }
      }
    } catch (Throwable outer) {
      log.warn("lease renewal tick failed: {}", outer.getMessage());
    }
  }

  @Override
  public void close() {
    log.info("LeaseRenewalScheduler stopping");
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      scheduler.shutdownNow();
    }
  }
}
