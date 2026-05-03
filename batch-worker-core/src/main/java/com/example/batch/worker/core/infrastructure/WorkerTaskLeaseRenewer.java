package com.example.batch.worker.core.infrastructure;

import com.example.batch.worker.core.support.TaskExecutionClient;
import com.example.batch.worker.core.support.TaskLeaseRenewItem;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时为所有 in-flight 任务续租：从 {@link ActiveTaskLeaseRegistry#snapshot} 取出当前活跃租约列表，按 ADR-016 打包为单次（或可配置
 * chunk）HTTP {@code renew-batch} 调 Orchestrator；fast-retry 仍逐条 {@code renew}。
 *
 * <p>若 Orchestrator 返回 false（如任务已被取消或超时驱逐），记录 warn 日志； 续租失败不中断执行——任务仍会继续运行并在完成时正常 report， 但
 * Orchestrator 侧可能已将其标记为失活并重新派发（罕见情况）。
 *
 * <p><b>R-4.4 a</b>：按 taskId 追踪连续失败次数，超阈值时 log.error + micrometer 计数， 让运维可以在 Orchestrator
 * 长时间不可达时告警。仍保持"不熔断"语义（激进熔断会级联）， 但不再静默——单次失败 warn、连续 N 次 error + counter、恢复后重置。
 *
 * <p><b>v6 hardening · 续期失败指数回退</b>：除了主周期（10s）固定续期外，新增 fast-retry 周期（默认 2s）， 仅处理 {@code
 * consecutiveFailures > 0} 的失败 lease。固定 10s 周期下若连续 3 次失败需 ~30s 才能告警， 期间 lease 可能已过期（默认 120s 的安全余量被
 * GC pause 蚕食）；fast-retry 把失败恢复时间压缩到 ~2s， 对网络抖动 / orchestrator 短暂不可达更友好。fast-retry 独立调度，正常路径无额外开销。
 */
@Slf4j
@Component
public class WorkerTaskLeaseRenewer {

  private static final String METRIC_CONSECUTIVE = "batch.worker.lease.consecutive_failures";
  private static final String METRIC_FAST_RETRY = "batch.worker.lease.fast_retry";
  private static final String METRIC_CIRCUIT_OPEN = "batch.worker.lease.circuit.open.total";

  private final ActiveTaskLeaseRegistry activeTaskLeaseRegistry;
  private final TaskExecutionClient taskExecutionClient;
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;
  // R-4.4 a: 按 taskId 维护连续失败计数器；成功 / remove 时清零
  private final Map<String, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();
  // P1-8: 进程级熔断 — 整轮 100% renew 失败时 OPEN,跳过后续 tick 直到半开探测;orch 恢复任一成功 → CLOSE
  private final java.util.concurrent.atomic.AtomicBoolean circuitOpen =
      new java.util.concurrent.atomic.AtomicBoolean(false);
  private final AtomicInteger ticksSinceOpen = new AtomicInteger(0);
  private volatile DistributionSummary renewBatchSizeSummary;

  @Value("${batch.worker.lease.consecutive-failure-alert-threshold:3}")
  private int alertThreshold;

  /** 熔断 OPEN 后每 N 个 tick 强制半开探测一次 (默认 5 = ~50s @ renew 周期 10s) */
  @Value("${batch.worker.lease.circuit-half-open-tick-interval:5}")
  private int circuitHalfOpenTickInterval;

  public WorkerTaskLeaseRenewer(
      ActiveTaskLeaseRegistry activeTaskLeaseRegistry,
      TaskExecutionClient taskExecutionClient,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.activeTaskLeaseRegistry = activeTaskLeaseRegistry;
    this.taskExecutionClient = taskExecutionClient;
    this.meterRegistryProvider = meterRegistryProvider;
  }

  @Scheduled(fixedDelayString = "${batch.worker.lease.renew-interval-millis:10000}")
  public void renewActiveTaskLeases() {
    // P1-8 熔断: OPEN 时跳过整 tick (避免持续 hammer 不可达 orch),每 circuitHalfOpenTickInterval
    // 个 tick 强制半开探测一次,任一 lease 成功即 CLOSE (恢复正常 renew)
    if (circuitOpen.get()) {
      int ticks = ticksSinceOpen.incrementAndGet();
      if (ticks < Math.max(1, circuitHalfOpenTickInterval)) {
        log.debug(
            "renew skipped: circuit OPEN (tick {}/{}); orch likely unreachable, awaiting cooldown",
            ticks,
            circuitHalfOpenTickInterval);
        return;
      }
      ticksSinceOpen.set(0);
      log.info("renew circuit half-open probe: attempting renewal");
    }
    Collection<ActiveTaskLeaseRegistry.ActiveTaskLease> active = activeTaskLeaseRegistry.snapshot();
    // L-5：清理 consecutiveFailures 里已不在活跃 lease 集合的条目，避免
    // "失败达到阈值后任务被 orchestrator 驱逐不再 renew" 路径永驻 AtomicInteger。
    // 按 taskId 比对当前活跃集 → 差集即是可回收的失败计数条目。
    if (!consecutiveFailures.isEmpty()) {
      Set<String> activeIds = new HashSet<>(active.size());
      for (ActiveTaskLeaseRegistry.ActiveTaskLease lease : active) {
        activeIds.add(lease.getTaskId());
      }
      consecutiveFailures.keySet().removeIf(taskId -> !activeIds.contains(taskId));
    }
    if (active.isEmpty()) {
      return;
    }
    List<ActiveTaskLeaseRegistry.ActiveTaskLease> leaseList = new ArrayList<>(active);
    List<TaskLeaseRenewItem> batchItems = new ArrayList<>(leaseList.size());
    for (ActiveTaskLeaseRegistry.ActiveTaskLease lease : leaseList) {
      batchItems.add(
          new TaskLeaseRenewItem(
              lease.getTenantId(),
              Long.valueOf(lease.getTaskId()),
              lease.getWorkerId(),
              lease.getPartitionInvocationId()));
    }
    recordRenewBatchSizeMetric(batchItems.size());
    Map<Long, Boolean> results = taskExecutionClient.renewLeasesBatch(batchItems);
    int success = 0;
    int failure = 0;
    for (ActiveTaskLeaseRegistry.ActiveTaskLease activeTaskLease : leaseList) {
      long taskIdLong = Long.parseLong(activeTaskLease.getTaskId());
      boolean renewed = results.getOrDefault(taskIdLong, false);
      if (renewed) {
        success++;
        consecutiveFailures.remove(activeTaskLease.getTaskId());
      } else {
        failure++;
        log.warn(
            "task lease renew rejected: tenantId={}, taskId={}, workerId={}, fastRetry={}",
            activeTaskLease.getTenantId(),
            activeTaskLease.getTaskId(),
            activeTaskLease.getWorkerId(),
            false);
        trackFailure(activeTaskLease, "rejected");
      }
    }
    // 熔断状态机: 全失败 → OPEN; 任一成功 (从 OPEN) → CLOSE
    if (failure == leaseList.size()) {
      if (circuitOpen.compareAndSet(false, true)) {
        log.error(
            "renew circuit OPENED: all {} renewals failed in this tick; orch likely unreachable —"
                + " skipping subsequent renew attempts until half-open probe ({} ticks)",
            failure,
            circuitHalfOpenTickInterval);
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
          Counter.builder(METRIC_CIRCUIT_OPEN).register(registry).increment();
        }
      }
    } else if (success > 0 && circuitOpen.compareAndSet(true, false)) {
      log.info(
          "renew circuit CLOSED: orch reachable again ({} success / {} failure)", success, failure);
      ticksSinceOpen.set(0);
    }
  }

  /**
   * 快速重试：仅处理上一轮主续期失败（{@code consecutiveFailures > 0}）的 lease，把恢复时间从 10s 周期压缩到 2s 周期。
   *
   * <p>与主续期独立调度，正常状态（无失败 lease）不会做任何工作。命中条目时记 metric 让运维观测网络抖动恢复速率。
   */
  @Scheduled(fixedDelayString = "${batch.worker.lease.fast-retry-interval-millis:2000}")
  public void fastRetryFailedLeases() {
    if (consecutiveFailures.isEmpty()) {
      return;
    }
    Collection<ActiveTaskLeaseRegistry.ActiveTaskLease> active = activeTaskLeaseRegistry.snapshot();
    for (ActiveTaskLeaseRegistry.ActiveTaskLease lease : active) {
      AtomicInteger counter = consecutiveFailures.get(lease.getTaskId());
      if (counter == null || counter.get() <= 0) {
        continue;
      }
      attemptRenew(lease, true);
    }
  }

  /**
   * @return true=本次 renew 成功 (用于熔断状态机判定); false=失败或被拒
   */
  private boolean attemptRenew(
      ActiveTaskLeaseRegistry.ActiveTaskLease activeTaskLease, boolean fastRetry) {
    try {
      boolean renewed =
          taskExecutionClient.renewLease(
              activeTaskLease.getTenantId(),
              Long.valueOf(activeTaskLease.getTaskId()),
              activeTaskLease.getWorkerId(),
              activeTaskLease.getPartitionInvocationId());
      if (!renewed) {
        log.warn(
            "task lease renew rejected: tenantId={}, taskId={}, workerId={}, fastRetry={}",
            activeTaskLease.getTenantId(),
            activeTaskLease.getTaskId(),
            activeTaskLease.getWorkerId(),
            fastRetry);
        trackFailure(activeTaskLease, "rejected");
        return false;
      } else {
        if (fastRetry && consecutiveFailures.containsKey(activeTaskLease.getTaskId())) {
          // fast-retry 救回了之前失败的续期：记 metric 让运维感知抖动恢复速率
          MeterRegistry registry = meterRegistryProvider.getIfAvailable();
          if (registry != null) {
            Counter.builder(METRIC_FAST_RETRY)
                .tags(Tags.of("tenantId", String.valueOf(activeTaskLease.getTenantId())))
                .register(registry)
                .increment();
          }
        }
        consecutiveFailures.remove(activeTaskLease.getTaskId());
        return true;
      }
    } catch (Exception exception) {
      log.warn(
          "task lease renew failed: tenantId={}, taskId={}, workerId={}, fastRetry={}, error={}",
          activeTaskLease.getTenantId(),
          activeTaskLease.getTaskId(),
          activeTaskLease.getWorkerId(),
          fastRetry,
          exception.getMessage(),
          exception);
      trackFailure(activeTaskLease, exception.getClass().getSimpleName());
      return false;
    }
  }

  /** ADR-015：report outbox poller 协调 — 续租熔断 OPEN 时暂停向 orchestrator 重投 REPORT。 */
  public boolean isRenewCircuitOpen() {
    return circuitOpen.get();
  }

  private void recordRenewBatchSizeMetric(int size) {
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (registry == null || size <= 0) {
      return;
    }
    DistributionSummary s = renewBatchSizeSummary;
    if (s == null) {
      synchronized (this) {
        s = renewBatchSizeSummary;
        if (s == null) {
          s =
              DistributionSummary.builder("batch.worker.lease.renew.batch.size")
                  .description("ADR-016: active leases per renew tick (before HTTP chunking)")
                  .register(registry);
          renewBatchSizeSummary = s;
        }
      }
    }
    s.record(size);
  }

  private void trackFailure(ActiveTaskLeaseRegistry.ActiveTaskLease lease, String reason) {
    int count =
        consecutiveFailures
            .computeIfAbsent(lease.getTaskId(), k -> new AtomicInteger())
            .incrementAndGet();
    if (count >= alertThreshold) {
      log.error(
          "task lease renew failed {} times in a row — Orchestrator likely unreachable:"
              + " tenantId={}, taskId={}, workerId={}, lastReason={}",
          count,
          lease.getTenantId(),
          lease.getTaskId(),
          lease.getWorkerId(),
          reason);
      MeterRegistry registry = meterRegistryProvider.getIfAvailable();
      if (registry != null) {
        Counter.builder(METRIC_CONSECUTIVE)
            .tags(Tags.of("tenantId", String.valueOf(lease.getTenantId()), "reason", reason))
            .register(registry)
            .increment();
      }
    }
  }
}
