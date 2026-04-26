package com.example.batch.worker.core.infrastructure;

import com.example.batch.worker.core.support.TaskExecutionClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时为所有 in-flight 任务续租：从 {@link ActiveTaskLeaseRegistry#snapshot} 取出当前 活跃租约列表，逐条调 Orchestrator 的
 * {@code renewLease} 接口延长任务心跳超时。
 *
 * <p>若 Orchestrator 返回 false（如任务已被取消或超时驱逐），记录 warn 日志； 续租失败不中断执行——任务仍会继续运行并在完成时正常 report， 但
 * Orchestrator 侧可能已将其标记为失活并重新派发（罕见情况）。
 *
 * <p><b>R-4.4 a</b>：按 taskId 追踪连续失败次数，超阈值时 log.error + micrometer 计数， 让运维可以在 Orchestrator
 * 长时间不可达时告警。仍保持"不熔断"语义（激进熔断会级联）， 但不再静默——单次失败 warn、连续 N 次 error + counter、恢复后重置。
 */
@Slf4j
@Component
public class WorkerTaskLeaseRenewer {

  private static final String METRIC_CONSECUTIVE = "batch.worker.lease.consecutive_failures";

  private final ActiveTaskLeaseRegistry activeTaskLeaseRegistry;
  private final TaskExecutionClient taskExecutionClient;
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;
  // R-4.4 a: 按 taskId 维护连续失败计数器；成功 / remove 时清零
  private final Map<String, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();

  @Value("${batch.worker.lease.consecutive-failure-alert-threshold:3}")
  private int alertThreshold;

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
    java.util.Collection<ActiveTaskLeaseRegistry.ActiveTaskLease> active =
        activeTaskLeaseRegistry.snapshot();
    // L-5：清理 consecutiveFailures 里已不在活跃 lease 集合的条目，避免
    // "失败达到阈值后任务被 orchestrator 驱逐不再 renew" 路径永驻 AtomicInteger。
    // 按 taskId 比对当前活跃集 → 差集即是可回收的失败计数条目。
    if (!consecutiveFailures.isEmpty()) {
      java.util.Set<String> activeIds = new java.util.HashSet<>(active.size());
      for (ActiveTaskLeaseRegistry.ActiveTaskLease lease : active) {
        activeIds.add(lease.getTaskId());
      }
      consecutiveFailures.keySet().removeIf(taskId -> !activeIds.contains(taskId));
    }
    for (ActiveTaskLeaseRegistry.ActiveTaskLease activeTaskLease : active) {
      try {
        boolean renewed =
            taskExecutionClient.renewLease(
                activeTaskLease.getTenantId(),
                Long.valueOf(activeTaskLease.getTaskId()),
                activeTaskLease.getWorkerId());
        if (!renewed) {
          log.warn(
              "task lease renew rejected: tenantId={}, taskId={}, workerId={}",
              activeTaskLease.getTenantId(),
              activeTaskLease.getTaskId(),
              activeTaskLease.getWorkerId());
          trackFailure(activeTaskLease, "rejected");
        } else {
          // 成功续期：清零计数器
          consecutiveFailures.remove(activeTaskLease.getTaskId());
        }
      } catch (Exception exception) {
        log.warn(
            "task lease renew failed: tenantId={}, taskId={}, workerId={}, error={}",
            activeTaskLease.getTenantId(),
            activeTaskLease.getTaskId(),
            activeTaskLease.getWorkerId(),
            exception.getMessage(),
            exception);
        trackFailure(activeTaskLease, exception.getClass().getSimpleName());
      }
    }
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
