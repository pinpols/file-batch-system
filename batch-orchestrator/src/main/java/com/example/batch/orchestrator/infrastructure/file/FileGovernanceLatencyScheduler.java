package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文件治理——延迟指标采集调度器。
 *
 * <p>默认每 30 秒触发一次，委托 {@link FileGovernanceScheduler#collectLatencyMetrics()}
 * 采集文件到达延迟与处理延迟等监控指标并写入 Redis 缓存。
 * ShedLock 锁名 {@code file_governance_latency}，最长持锁 2 分钟，最短持锁 15 秒，
 * 保证多节点部署下只有单节点执行采集。Orchestrator 优雅停机时跳过。
 */
@Component
@RequiredArgsConstructor
public class FileGovernanceLatencyScheduler {

  private final FileGovernanceScheduler fileGovernanceScheduler;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.file-governance.latency.poll-interval-millis:30000}")
  @SchedulerLock(name = "file_governance_latency", lockAtMostFor = "PT2M", lockAtLeastFor = "PT15S")
  public void collectLatencyMetrics() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    fileGovernanceScheduler.collectLatencyMetrics();
  }
}
