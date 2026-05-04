package com.example.batch.orchestrator.infrastructure.startup;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.orchestrator.config.WorkerDrainProperties;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Orchestrator 启动后租约审计：读取 worker_registry / job_partition / outbox_event 里 <b>已经过期但仍未被回收</b> 的记录并以
 * WARN 日志告知运维。
 *
 * <p>审计范围：
 *
 * <ul>
 *   <li>{@code worker_registry} 处于 DRAINING 且 {@code drain_deadline_at < now()} —— 正常情况由 {@code
 *       WorkerDrainTimeoutScheduler} 接管，非 0 说明上次调度挂了/未来得及跑；
 *   <li>{@code job_partition} 为 {@code READY}/{@code RUNNING}、仍持有 {@code lease_expire_at} 且早于
 *       {@code now()} —— 与 {@code PartitionLeaseReclaimScheduler} 扫描口径一致；终态分区残留 lease 不计；
 *   <li>{@code outbox_event} 卡在 PUBLISHING 且 updated_at 超 10 分钟 —— 正常情况由 OutboxPoll {@code
 *       resetStalePublishing} 每轮清 0，非 0 说明轮询本身挂了。
 * </ul>
 *
 * <p>本组件 <b>只告警不修复</b>：修复交给各自的定时调度器（启动后几十秒内会自动跑第一轮）。 价值在于"新启动的 Pod 立即给出一个健康快照"，便于排查"刚拉起就已有残留"这类场景。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestratorStartupLeaseAudit {

  /** outbox 卡住判定阈值：与原 SQL `current_timestamp - interval '10 minutes'` 等价。 */
  private static final long OUTBOX_STUCK_THRESHOLD_SECONDS = 600L;

  private final WorkerRegistryMapper workerRegistryMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final JobPartitionMapper jobPartitionMapper;
  private final OutboxEventMapper outboxEventMapper;
  private final WorkerDrainProperties workerDrainProperties;

  @EventListener(ApplicationReadyEvent.class)
  public void audit() {
    try {
      long drainingStale = workerRegistryMapper.countDrainingPastDeadline();
      long staleOnlineWorkers =
          workerRegistryMapper.countStaleOnline(
              (long) workerDrainProperties.getHeartbeatTimeoutSeconds()
                  + workerDrainProperties.getHeartbeatGraceSeconds());
      long decommissionedActiveClaims = workerRegistryMapper.countDecommissionedWithActiveTasks();
      long invalidCapabilityTags = workerRegistryMapper.countInvalidCapabilityTags();
      long terminalActiveChildren = jobInstanceMapper.countTerminalInstancesWithActiveChildren();
      long leasesExpired =
          jobPartitionMapper.countLeaseExpired(
              PartitionStatus.READY.code(), PartitionStatus.RUNNING.code());
      long outboxStuck =
          outboxEventMapper.countStalePublishing(
              OutboxPublishStatus.PUBLISHING.code(), OUTBOX_STUCK_THRESHOLD_SECONDS);

      if (drainingStale == 0
          && staleOnlineWorkers == 0
          && decommissionedActiveClaims == 0
          && invalidCapabilityTags == 0
          && terminalActiveChildren == 0
          && leasesExpired == 0
          && outboxStuck == 0) {
        log.info(
            "启动运行态审计通过（orchestrator）：无 stale worker / drain overdue / active decommissioned"
                + " claims / invalid capability_tags / terminal active children / 过期租约 / 卡死"
                + " PUBLISHING");
        return;
      }

      log.warn(
          "启动运行态审计发现残留（orchestrator）：drainingStale={}, staleOnlineWorkers={},"
              + " decommissionedActiveClaims={}, invalidCapabilityTags={},"
              + " terminalActiveChildren={}, leasesExpired={}, outboxStuck={}"
              + "—— 本次审计仅告警，修复交给 WorkerDrainTimeoutScheduler / PartitionLeaseReclaimScheduler"
              + " / OutboxPollScheduler / JobInstanceTerminalChildStateReconciler 关联路径自动完成；"
              + "如非预期请排查对应调度器状态。",
          drainingStale,
          staleOnlineWorkers,
          decommissionedActiveClaims,
          invalidCapabilityTags,
          terminalActiveChildren,
          leasesExpired,
          outboxStuck);
    } catch (RuntimeException ex) {
      // 审计失败不阻塞应用启动；可能是首次启动时部分表未建（Flyway 还没跑完）
      log.warn("启动租约审计执行失败（不影响启动）：{}", ex.getMessage());
    }
  }
}
