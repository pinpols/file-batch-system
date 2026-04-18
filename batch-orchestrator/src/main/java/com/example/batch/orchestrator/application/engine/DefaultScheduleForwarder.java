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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox → Kafka 批量推送：把已落库的 {@code outbox_event} 分批发布，并按结果更新状态与重试记录。
 *
 * <p>采用三阶段批处理以解耦"DB 行锁"与"Kafka RTT"：
 *
 * <ol>
 *   <li><b>阶段一：batch markPublishing</b>。逐条 CAS 推到 PUBLISHING，立刻发起异步 send；
 *       CAS 成功即抢到独占权，天然排斥其他 forwarder 并发投递同一事件，且此时不等待 ACK，不再持有行锁过网络调用。
 *   <li><b>阶段二：并行等 ACK</b>。{@code allOf.join()} 等本批所有 future 完成，耗时 ≈ 单条最长 RTT。
 *       失败或超时的 future 返回 false，由阶段三统一降级，不在此处阻塞重试。
 *   <li><b>阶段三：batch DB 更新</b>。按结果写 PUBLISHED / FAILED / GIVE_UP，并通过 {@link #recordRetry}
 *       落 {@code event_outbox_retry}——这是"发布者级 I/O 重试"，与业务级任务重试（{@code retry_schedule}）分开管理。
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class DefaultScheduleForwarder implements ScheduleForwarder {

  private final OutboxEventMapper outboxEventMapper;
  private final EventOutboxRetryMapper eventOutboxRetryMapper;
  private final OutboxPublisher outboxPublisher;
  private final BatchOrchestratorGovernanceProperties governance;

  @Override
  @Transactional
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
        nextRetryAt == null ? RetryScheduleStatus.EXHAUSTED.code() : RetryScheduleStatus.FAILED.code());
    retry.setRetryReason(reason);
    retry.setNextRetryAt(nextRetryAt);
    retry.setTraceId(event.getTraceId());
    eventOutboxRetryMapper.insert(retry);
  }
}
