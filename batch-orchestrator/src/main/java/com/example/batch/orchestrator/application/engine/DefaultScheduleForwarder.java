package com.example.batch.orchestrator.application.engine;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.enums.RetryScheduleStatus;
import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.EventOutboxRetryEntity;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.domain.query.OutboxEventQuery;
import com.example.batch.orchestrator.mapper.EventOutboxRetryMapper;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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

  @Override
  @Retryable(
      retryFor = {
        CannotAcquireLockException.class,
        TransientDataAccessException.class,
        QueryTimeoutException.class
      },
      maxAttempts = 3,
      backoff = @Backoff(delay = 100, multiplier = 2, maxDelay = 1000))
  public ScheduleForwarderResult advance(SchedulePlan plan) {
    List<OutboxEventEntity> pendingEvents =
        outboxEventMapper.selectPending(
            new OutboxEventQuery(
                plan == null ? null : plan.getTenantId(),
                null,
                null,
                null,
                OutboxPublishStatus.NEW.code(),
                OutboxPublishStatus.FAILED.code(),
                governance.outbox().getBatchSize(),
                plan == null ? 1 : plan.getShardTotal(),
                plan == null ? 0 : plan.getShardIndex()));
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
    if (!inFlight.isEmpty()) {
      CompletableFuture.allOf(
              inFlight.stream().map(InFlight::future).toArray(CompletableFuture[]::new))
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
              event.getTenantId(), event.getId(), OutboxPublishStatus.PUBLISHED.code());
        } else {
          publishFailed++;
          Instant nextRetryAt =
              Instant.now().plusSeconds(governance.outbox().getRetryDelaySeconds());
          int publishAttemptNo =
              event.getPublishAttempt() == null ? 1 : event.getPublishAttempt() + 1;
          if (publishAttemptNo >= governance.outbox().getMaxRetryAttempts()) {
            outboxEventMapper.markGiveUp(
                event.getTenantId(), event.getId(), OutboxPublishStatus.GIVE_UP.code());
            recordRetry(event, publishAttemptNo, null, "retry attempts exhausted");
          } else {
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
