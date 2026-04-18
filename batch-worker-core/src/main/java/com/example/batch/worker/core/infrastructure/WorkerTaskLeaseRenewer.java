package com.example.batch.worker.core.infrastructure;

import com.example.batch.worker.core.support.TaskExecutionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时为所有 in-flight 任务续租：从 {@link ActiveTaskLeaseRegistry#snapshot} 取出当前
 * 活跃租约列表，逐条调 Orchestrator 的 {@code renewLease} 接口延长任务心跳超时。
 *
 * <p>若 Orchestrator 返回 false（如任务已被取消或超时驱逐），记录 warn 日志；
 * 续租失败不中断执行——任务仍会继续运行并在完成时正常 report，
 * 但 Orchestrator 侧可能已将其标记为失活并重新派发（罕见情况）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerTaskLeaseRenewer {

  private final ActiveTaskLeaseRegistry activeTaskLeaseRegistry;
  private final TaskExecutionClient taskExecutionClient;

  @Scheduled(fixedDelayString = "${batch.worker.lease.renew-interval-millis:10000}")
  public void renewActiveTaskLeases() {
    for (ActiveTaskLeaseRegistry.ActiveTaskLease activeTaskLease :
        activeTaskLeaseRegistry.snapshot()) {
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
        }
      } catch (Exception exception) {
        log.warn(
            "task lease renew failed: tenantId={}, taskId={}, workerId={}, error={}",
            activeTaskLease.getTenantId(),
            activeTaskLease.getTaskId(),
            activeTaskLease.getWorkerId(),
            exception.getMessage(),
            exception);
      }
    }
  }
}
