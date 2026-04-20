package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.orchestrator.config.WorkerDrainProperties;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker 心跳超时治理调度器。
 *
 * <p>背景：{@code worker_registry.heartbeat_at} 由 worker 自己通过 {@code touchHeartbeat} 定期刷新；
 * 进程崩溃 / 网络隔离 / JVM 卡死时心跳停更，但 DB 里 {@code status} 仍停留在 ONLINE。 若无外力清理，
 * {@code DefaultWorkerSelector} 会选中"僵尸 worker"并把 partition release 给它——partition 永远不会被
 * claim，堆积在 READY。
 *
 * <p>本 scheduler 每 {@code heartbeat-check-interval-millis}（默认 30s）扫一次，
 * 把 {@code heartbeat_at < now - heartbeat-timeout-seconds}（默认 90s）且处于 ONLINE / DRAINING
 * 的 worker 批量降级为 {@code OFFLINE}；DECOMMISSIONED 已由运维/人工终止，<b>不复活</b>。
 *
 * <p><b>阈值选择</b>：worker 的默认心跳周期通常是 10~20s，30s 扫 + 90s 超时留出 2~3 次漏跳容忍， 避免短暂抖动误伤。
 *
 * <p><b>集群并发</b>：ShedLock({@code worker_heartbeat_timeout}, PT2M)；单次扫描是纯批量 UPDATE， 通常 &lt;
 * 100ms，2 分钟持锁上限远超预期。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerHeartbeatTimeoutScheduler {

  private final WorkerRegistryMapper workerRegistryMapper;
  private final WorkerDrainProperties workerDrainProperties;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.worker.drain.heartbeat-check-interval-millis:30000}")
  @SchedulerLock(
      name = "worker_heartbeat_timeout",
      lockAtMostFor = "PT2M",
      lockAtLeastFor = "PT5S")
  public void markStaleWorkersOffline() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    Instant cutoff =
        Instant.now().minusSeconds(workerDrainProperties.getHeartbeatTimeoutSeconds());
    int updated = workerRegistryMapper.markStaleHeartbeatsOffline(cutoff);
    if (updated > 0) {
      log.info(
          "worker heartbeat timeout: marked {} workers OFFLINE (cutoff={}, timeoutSeconds={})",
          updated,
          cutoff,
          workerDrainProperties.getHeartbeatTimeoutSeconds());
    }
  }
}
