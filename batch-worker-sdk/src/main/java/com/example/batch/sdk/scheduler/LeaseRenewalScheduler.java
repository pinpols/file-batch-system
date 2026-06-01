package com.example.batch.sdk.scheduler;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.internal.PlatformHttpException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * 任务 lease 续约 — 按 {@link BatchPlatformClientConfig#getLeaseRenewInterval()} 周期遍历 dispatcher 的
 * {@link TaskDispatcher#inFlightTaskIds() in-flight set},对每个 taskId 调 {@code
 * /internal/tasks/{id}/renew-lease}。
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
        renewOne(taskId);
      }
    } catch (Throwable outer) {
      log.warn("lease renewal tick failed: {}", outer.getMessage());
    }
  }

  /**
   * 续约单个 task,并消费 renew response:
   *
   * <ul>
   *   <li>{@code cancelRequested=true}(运维 cancel / ORCH-P4-2 超时)→ 翻转该 task 取消信号,handler 主动停。
   *   <li>lease 被回收(404 / 410)→ task 已被平台回收重派,同样翻转取消信号让本地副本停,避免双跑。
   *   <li>其它失败 → warn,下轮再试(或自然走完 dispatcher 流程)。
   * </ul>
   */
  private void renewOne(Long taskId) {
    try {
      // body 对齐 TaskHeartbeatRequest(tenantId/workerId/partitionInvocationId/details)
      Map<String, Object> body = new HashMap<>();
      body.put("tenantId", config.getTenantId());
      body.put("workerId", config.getWorkerCode());
      Map<String, Object> details = dispatcher.progressSnapshot(taskId);
      if (details != null) {
        body.put("details", details); // SDK-P4-2:handler reportProgress 快照,落 job_task
      }
      Map<String, Object> resp = httpClient.renew(taskId, body);
      if (resp != null && Boolean.TRUE.equals(resp.get("cancelRequested"))) {
        dispatcher.markCancelled(taskId, "platform-cancel");
      }
    } catch (PlatformHttpException httpEx) {
      if (httpEx.statusCode() == 404 || httpEx.statusCode() == 410) {
        log.warn(
            "lease revoked for taskId={} (HTTP {}), signalling stop", taskId, httpEx.statusCode());
        dispatcher.markCancelled(taskId, "lease-revoked");
      } else {
        log.warn("renew failed for taskId={} (HTTP {})", taskId, httpEx.statusCode());
      }
    } catch (Throwable t) {
      log.warn("renew failed for taskId={}: {}", taskId, t.getMessage());
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
