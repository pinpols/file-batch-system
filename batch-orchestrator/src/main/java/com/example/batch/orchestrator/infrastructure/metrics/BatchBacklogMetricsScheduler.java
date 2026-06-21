package com.example.batch.orchestrator.infrastructure.metrics;

import com.example.batch.common.enums.DeadLetterReplayStatus;
import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.DeadLetterTaskMapper;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox / DLQ 积压量指标收集器。
 *
 * <p>暴露 3 个 Micrometer gauge 供 Prometheus 抓取：
 *
 * <ul>
 *   <li>{@code batch.outbox.pending.events}：NEW + FAILED 状态的 Outbox 事件总数（积压量）
 *   <li>{@code batch.outbox.publishing.stale.events}：卡在 PUBLISHING 且超时的事件数 （正常情况下 {@code
 *       resetStalePublishing} 清 0；非 0 说明 poll 本身挂了）
 *   <li>{@code batch.outbox.duplicate.event_keys}：近窗口内重复 (tenant_id,event_key) 组数。分区 outbox 放弃全局
 *       UNIQUE 后,此指标必须长期为 0
 *   <li>{@code batch.dead_letter.tasks.pending}：NEW + FAILED 状态的死信数
 * </ul>
 *
 * <p>默认 30 秒扫一次，通过 ShedLock 保证多 Pod 下只有一个实例执行；可通过 {@code batch.metrics.backlog.enabled=false} 关闭。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Lazy(false)
public class BatchBacklogMetricsScheduler {

  private static final List<String> OUTBOX_PENDING_STATUSES =
      List.of(OutboxPublishStatus.NEW.code(), OutboxPublishStatus.FAILED.code());
  private static final List<String> DLQ_PENDING_STATUSES =
      List.of(DeadLetterReplayStatus.NEW.code(), DeadLetterReplayStatus.FAILED.code());

  private final OutboxEventMapper outboxEventMapper;
  private final DeadLetterTaskMapper deadLetterTaskMapper;
  private final BatchOrchestratorGovernanceProperties governance;
  private final MeterRegistry meterRegistry;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Value("${batch.metrics.backlog.outbox-duplicate-window-hours:24}")
  private long outboxDuplicateWindowHours;

  private final AtomicLong outboxPending = new AtomicLong();
  private final AtomicLong outboxStalePublishing = new AtomicLong();
  private final AtomicLong outboxDuplicateEventKeys = new AtomicLong();
  private final AtomicLong dlqPending = new AtomicLong();

  @PostConstruct
  void initializeMeters() {
    meterRegistry.gauge("batch.outbox.pending.events", outboxPending);
    meterRegistry.gauge("batch.outbox.publishing.stale.events", outboxStalePublishing);
    meterRegistry.gauge("batch.outbox.duplicate.event_keys", outboxDuplicateEventKeys);
    meterRegistry.gauge("batch.dead_letter.tasks.pending", dlqPending);
  }

  @Scheduled(fixedDelayString = "${batch.metrics.backlog.poll-interval-millis:30000}")
  @SchedulerLock(name = "batch_backlog_metrics", lockAtMostFor = "PT1M", lockAtLeastFor = "PT15S")
  public void sample() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    try {
      outboxPending.set(outboxEventMapper.countByStatuses(OUTBOX_PENDING_STATUSES));
      outboxStalePublishing.set(
          outboxEventMapper.countStalePublishing(
              OutboxPublishStatus.PUBLISHING.code(),
              governance.outbox().getPublishingTimeoutSeconds()));
      outboxDuplicateEventKeys.set(
          outboxEventMapper.countDuplicateEventKeysSince(
              BatchDateTimeSupport.utcNow().minus(duplicateDetectionWindow())));
      dlqPending.set(deadLetterTaskMapper.countByReplayStatuses(DLQ_PENDING_STATUSES));
    } catch (RuntimeException ex) {
      log.warn("batch backlog metrics sampling failed: {}", ex.getMessage());
    }
  }

  private Duration duplicateDetectionWindow() {
    return Duration.ofHours(Math.max(1, outboxDuplicateWindowHours));
  }
}
