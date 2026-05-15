package com.example.batch.orchestrator.application.engine;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.enums.RetryScheduleStatus;
import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.config.OutboxProperties;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.EventOutboxRetryEntity;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.domain.query.OutboxEventQuery;
import com.example.batch.orchestrator.mapper.EventOutboxRetryMapper;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * Outbox → Kafka 批量推送：把已落库的 {@code outbox_event} 分批发布，并按结果更新状态与重试记录。
 *
 * <p>采用三阶段批处理以解耦"DB 连接 + 行锁"与"Kafka RTT"：
 *
 * <ol>
 *   <li><b>阶段一：batch markPublishing</b>。逐条 CAS 推到 PUBLISHING，立刻发起异步 send； CAS 成功即抢到独占权，天然排斥其他
 *       forwarder 并发投递同一事件，且此时不等待 ACK，不再持有行锁过网络调用。
 *   <li><b>阶段二：并行等 ACK</b>。{@code allOf.join()} 等本批所有 future 完成，耗时 ≈ 单条最长 RTT。 失败或超时的 future 返回
 *       false，由阶段三统一降级，不在此处阻塞重试。
 *   <li><b>阶段三：batch DB 更新</b>。按结果写 PUBLISHED / FAILED / GIVE_UP，并通过 {@link #recordRetry} 落 {@code
 *       event_outbox_retry}——这是"发布者级 I/O 重试"，与业务级任务重试（{@code retry_schedule}）分开管理。
 * </ol>
 *
 * <p><b>事务策略</b>：本方法 <b>不</b> 用 {@code @Transactional} 包裹整个流程。原因：
 *
 * <ul>
 *   <li>并发安全由 {@code markPublishing} 的 CAS 保证（单条 UPDATE 自带原子性），不依赖事务隔离；
 *   <li>若外层统一 {@code @Transactional}，阶段二 {@code allOf.join()} 等 Kafka ACK 期间 会持住 DB 连接（但不在用），连接池连续
 *       ≥ leak-detection-threshold（30s）即报 leak， 和"解耦 DB 连接与 Kafka RTT"的设计意图直接冲突；
 *   <li>MyBatis 在无 Spring 事务时对每条 SQL 自动借-用-还连接，阶段一/阶段三各条 UPDATE 独立提交，连接持有时长压到单次 SQL 级别（毫秒）。
 * </ul>
 *
 * <p>唯一副作用：{@link #recordRetry} 与对应的 {@code markFailed} 不再原子。JVM 恰好在两者之间 崩溃时，{@code
 * event_outbox_retry} 审计表会丢一行（该表仅供 console-api 展示/导出， 不参与重试调度判断）—— 下一轮 {@code selectPending} 仍会按
 * outbox 状态重新拾起事件。
 */
@Service
@RequiredArgsConstructor
public class DefaultScheduleForwarder implements ScheduleForwarder {

  private final OutboxEventMapper outboxEventMapper;
  private final EventOutboxRetryMapper eventOutboxRetryMapper;
  private final OutboxPublisher outboxPublisher;
  private final BatchOrchestratorGovernanceProperties governance;
  private final MeterRegistry meterRegistry;

  /** GIVE_UP 终态告警计数器 — 转入 GIVE_UP 即 +1,运维侧通过 Prometheus alert 拉起。 */
  private Counter giveUpCounter;

  @PostConstruct
  void initMetrics() {
    giveUpCounter =
        // R7-A6-P1：与 trigger 端 batch.trigger.outbox.give_up.total + 领域字典 GIVE_UP 命名对齐。
        Counter.builder("batch.outbox.events.give_up.total")
            .description(
                "Outbox events transitioned to GIVE_UP terminal state — should be 0 in steady"
                    + " state; non-zero rate triggers ops alert.")
            .register(meterRegistry);
  }

  @Override
  @Timed(
      value = "batch.outbox.publish.duration",
      description = "Outbox batch publish latency including Kafka acknowledgements",
      histogram = true)
  @Retryable(
      retryFor = {
        CannotAcquireLockException.class,
        TransientDataAccessException.class,
        QueryTimeoutException.class
      },
      maxAttempts = 3,
      backoff = @Backoff(delay = 100, multiplier = 2, maxDelay = 1000))
  public ScheduleForwarderResult advance(SchedulePlan plan) {
    OutboxEventQuery pendingQuery =
        OutboxEventQuery.builder()
            .tenantId(plan == null ? null : plan.getTenantId())
            .pendingStatus1(OutboxPublishStatus.NEW.code())
            .pendingStatus2(OutboxPublishStatus.FAILED.code())
            .batchSize(governance.outbox().getBatchSize())
            .shardTotal(plan == null ? 1 : plan.getShardTotal())
            .shardIndex(plan == null ? 0 : plan.getShardIndex())
            .build();
    List<OutboxEventEntity> pendingEvents = outboxEventMapper.selectPending(pendingQuery);
    // ── 阶段一：批量 markPublishing + 并行触发 Kafka 发送 ──────────────────────
    record InFlight(OutboxEventEntity event, CompletableFuture<Boolean> future) {}
    List<InFlight> inFlight = new ArrayList<>(pendingEvents.size());
    for (OutboxEventEntity event : pendingEvents) {
      if (outboxEventMapper.markPublishing(
              event.getTenantId(),
              event.getId(),
              OutboxPublishStatus.PUBLISHING.code(),
              OutboxPublishStatus.NEW.code(),
              OutboxPublishStatus.FAILED.code())
          > 0) {
        CompletableFuture<Boolean> future = outboxPublisher.publish(event);
        inFlight.add(
            new InFlight(
                event, future != null ? future : CompletableFuture.completedFuture(false)));
      }
    }

    // ── 阶段二：等待本批所有 Kafka ACK（并行等待，耗时 ≈ 单条最长 RTT）────────
    // R7-A2-P0：用 exceptionally 吞掉单条 future 的异常，否则 allOf.join() 抛 CompletionException
    // 会让整个 try 块上抛、阶段三完全跳过，整批事件原地停在 PUBLISHING 状态，要等 stale TTL
    // (`resetStalePublishing`) 才能回收 — 实测会让 trigger 高峰积压瞬间放大。阶段三里的
    // `future.getNow(false)` 已经对失败结果（false / 仍未完成）做正确处理，这里只负责等齐。
    if (!inFlight.isEmpty()) {
      CompletableFuture.allOf(
              inFlight.stream().map(InFlight::future).toArray(CompletableFuture[]::new))
          .exceptionally(ex -> null)
          .join();
    }

    // ── 阶段三：根据发送结果统一更新 DB 状态 ───────────────────────────────────
    int attemptedEvents = 0;
    int publishSucceeded = 0;
    int publishFailed = 0;
    for (InFlight item : inFlight) {
      OutboxEventEntity event = item.event();
      BatchMdc.put(StructuredLogField.TENANT_ID, event.getTenantId());
      BatchMdc.put(StructuredLogField.TRACE_ID, event.getTraceId());
      try {
        attemptedEvents++;
        boolean published = Boolean.TRUE.equals(item.future().getNow(false));
        if (published) {
          publishSucceeded++;
          outboxEventMapper.markPublished(
              event.getTenantId(),
              event.getId(),
              OutboxPublishStatus.PUBLISHED.code(),
              OutboxPublishStatus.PUBLISHING.code());
        } else {
          publishFailed++;
          int publishAttemptNo =
              event.getPublishAttempt() == null ? 1 : event.getPublishAttempt() + 1;
          if (publishAttemptNo >= governance.outbox().getMaxRetryAttempts()) {
            outboxEventMapper.markGiveUp(
                event.getTenantId(), event.getId(), OutboxPublishStatus.GIVE_UP.code());
            recordRetry(event, publishAttemptNo, null, "retry attempts exhausted");
            giveUpCounter.increment();
          } else {
            Instant nextRetryAt = computeNextRetryAt(publishAttemptNo, governance.outbox());
            outboxEventMapper.markFailed(
                event.getTenantId(), event.getId(), OutboxPublishStatus.FAILED.code(), nextRetryAt);
            recordRetry(event, publishAttemptNo, nextRetryAt, "publish failed");
          }
        }
      } finally {
        BatchMdc.remove(StructuredLogField.TENANT_ID);
        BatchMdc.remove(StructuredLogField.TRACE_ID);
      }
    }
    return ScheduleForwarderResult.of(attemptedEvents, publishSucceeded, publishFailed);
  }

  /**
   * 计算下次重试时间(指数退避 + jitter,2026-05-01 加)。
   *
   * <p>公式:{@code delay = clamp(base × multiplier^(attempt-1), [base, max]) × (1 ± jitter)}
   *
   * <p>例(默认 base=60s, multiplier=2, max=600s, jitter=0.2):attempt 1 → 60s±12s; attempt 2 →
   * 120s±24s; attempt 3 → 240s±48s; attempt 4 → 480s±96s; attempt 5(最后一次失败前) → 600s±120s。
   *
   * <p>visible-for-testing:暴露包私有给单测验证边界。
   */
  static Instant computeNextRetryAt(int attemptNo, OutboxProperties outbox) {
    long base = Math.max(outbox.getRetryDelaySeconds(), 1L);
    double multiplier = Math.max(outbox.getRetryBackoffMultiplier(), 1.0);
    long max = Math.max(outbox.getRetryMaxDelaySeconds(), base);
    int normalizedAttempt = Math.max(attemptNo, 1);
    // attempt^(N-1) 用 double 防溢出,clamp 到 [base, max]
    double raw = base * Math.pow(multiplier, normalizedAttempt - 1);
    long bounded = Math.min(Math.max((long) raw, base), max);
    double jitterRatio = Math.max(0.0, Math.min(outbox.getRetryJitterRatio(), 1.0));
    long jittered;
    if (jitterRatio == 0.0) {
      jittered = bounded;
    } else {
      double jitterFactor =
          1.0 + (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * jitterRatio;
      jittered = Math.max((long) (bounded * jitterFactor), 1L);
    }
    return BatchDateTimeSupport.utcNow().plusSeconds(jittered);
  }

  /** 单独持久化 Outbox 发布重试记录，与业务重试计数器分开管理。 */
  private void recordRetry(
      OutboxEventEntity event, int publishAttemptNo, Instant nextRetryAt, String reason) {
    EventOutboxRetryEntity retry = new EventOutboxRetryEntity();
    retry.setTenantId(event.getTenantId());
    retry.setOutboxEventId(event.getId());
    retry.setEventKey(event.getEventKey());
    retry.setPublishAttempt(publishAttemptNo);
    retry.setRetryStatus(
        nextRetryAt == null
            ? RetryScheduleStatus.EXHAUSTED.code()
            : RetryScheduleStatus.FAILED.code());
    retry.setRetryReason(reason);
    retry.setNextRetryAt(nextRetryAt);
    retry.setTraceId(event.getTraceId());
    eventOutboxRetryMapper.insert(retry);
  }
}
