package io.github.pinpols.batch.orchestrator.infrastructure.metrics;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.orchestrator.domain.entity.QueuePartitionBacklogStats;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 调度队列积压采样器。
 *
 * <p>这里只暴露全局低基数 gauge；租户/队列维度明细由 scheduler snapshot API 提供，避免 Prometheus tag 维度随租户和队列膨胀。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerQueueBacklogMetricsScheduler {

  private final JobPartitionMapper jobPartitionMapper;
  private final MeterRegistry meterRegistry;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  private final AtomicLong createdPartitions = new AtomicLong();
  private final AtomicLong waitingPartitions = new AtomicLong();
  private final AtomicLong readyPartitions = new AtomicLong();
  private final AtomicLong runningPartitions = new AtomicLong();
  private final AtomicLong retryingPartitions = new AtomicLong();
  private final AtomicLong queuedPartitions = new AtomicLong();
  private final AtomicLong oldestWaitingSeconds = new AtomicLong();

  @PostConstruct
  void initializeMeters() {
    meterRegistry.gauge("batch.orchestrator.scheduler.queue.created.partitions", createdPartitions);
    meterRegistry.gauge("batch.orchestrator.scheduler.queue.waiting.partitions", waitingPartitions);
    meterRegistry.gauge("batch.orchestrator.scheduler.queue.ready.partitions", readyPartitions);
    meterRegistry.gauge("batch.orchestrator.scheduler.queue.running.partitions", runningPartitions);
    meterRegistry.gauge(
        "batch.orchestrator.scheduler.queue.retrying.partitions", retryingPartitions);
    meterRegistry.gauge("batch.orchestrator.scheduler.queue.queued.partitions", queuedPartitions);
    meterRegistry.gauge(
        "batch.orchestrator.scheduler.queue.oldest_wait.seconds", oldestWaitingSeconds);
  }

  @Scheduled(fixedDelayString = "${batch.metrics.scheduler-queue.poll-interval-millis:30000}")
  @SchedulerLock(
      name = "scheduler_queue_backlog_metrics",
      lockAtMostFor = "PT1M",
      lockAtLeastFor = "PT10S")
  public void sample() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    try {
      QueuePartitionBacklogStats stats =
          jobPartitionMapper.summarizeGlobalQueueBacklog(
              PartitionStatus.CREATED.code(),
              PartitionStatus.WAITING.code(),
              PartitionStatus.READY.code(),
              PartitionStatus.RUNNING.code(),
              PartitionStatus.RETRYING.code());
      if (stats == null) {
        stats = new QueuePartitionBacklogStats("ALL", 0, 0, 0, 0, 0, 0);
      }
      createdPartitions.set(stats.createdPartitions());
      waitingPartitions.set(stats.waitingPartitions());
      readyPartitions.set(stats.readyPartitions());
      runningPartitions.set(stats.runningPartitions());
      retryingPartitions.set(stats.retryingPartitions());
      queuedPartitions.set(stats.queuedPartitions());
      oldestWaitingSeconds.set(stats.oldestWaitingSeconds());
    } catch (RuntimeException ex) {
      log.warn("scheduler queue backlog metrics sampling failed: {}", ex.getMessage());
    }
  }
}
