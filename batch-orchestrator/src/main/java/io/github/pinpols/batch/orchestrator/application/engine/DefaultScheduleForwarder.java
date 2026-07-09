package io.github.pinpols.batch.orchestrator.application.engine;

import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.enums.RetryScheduleStatus;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import io.github.pinpols.batch.orchestrator.config.OutboxProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.EventOutboxRetryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.OutboxEventEntity;
import io.github.pinpols.batch.orchestrator.domain.query.OutboxEventQuery;
import io.github.pinpols.batch.orchestrator.mapper.EventOutboxRetryMapper;
import io.github.pinpols.batch.orchestrator.mapper.OutboxEventMapper;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * Outbox → Kafka 批量推送：把已写入数据库的 {@code outbox_event} 分批发布，并按结果更新状态与重试记录。
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
@Slf4j
public class DefaultScheduleForwarder implements ScheduleForwarder {

  private final OutboxEventMapper outboxEventMapper;
  private final EventOutboxRetryMapper eventOutboxRetryMapper;
  private final OutboxPublisher outboxPublisher;
  private final BatchOrchestratorGovernanceProperties governance;
  private final MeterRegistry meterRegistry;

  /** GIVE_UP 终态告警计数器 — 转入 GIVE_UP 即 +1,运维侧通过 Prometheus alert 拉起。 */
  private Counter giveUpCounter;

  /**
   * F P1:DB select 耗时(只测 selectPending 的 DB 部分)。
   *
   * <p>与现有 {@code batch.outbox.publish.duration}(@Timed,poll+send 合体)区分:当告警显示 publish.duration P95
   * 上升时,看 poll.duration 即可定位是 DB 慢(索引/连接池/锁)还是 Kafka 慢(broker/网络)。
   */
  private Timer pollTimer;

  /**
   * A6:每轮 outbox 批填充规模。{@code attempted} 长期贴近 {@code batchSize} 上限=饱和信号(积压未消化, 应调大 batchSize
   * 或加分片);{@code succeeded} 与 attempted 的差=Kafka 侧失败/重试压力。
   */
  private DistributionSummary batchAttemptedSummary;

  private DistributionSummary batchSucceededSummary;

  @PostConstruct
  void initMetrics() {
    giveUpCounter =
        // R7-A6-P1：与 trigger 端 batch.trigger.outbox.give_up.total + 领域字典 GIVE_UP 命名对齐。
        Counter.builder("batch.outbox.events.give_up.total")
            .description(
                "Outbox events transitioned to GIVE_UP terminal state — should be 0 in steady"
                    + " state; non-zero rate triggers ops alert.")
            .register(meterRegistry);
    pollTimer =
        Timer.builder("batch.outbox.poll.duration")
            .description(
                "Outbox DB selectPending latency — isolated from Kafka send to diagnose slow"
                    + " queries vs slow brokers when publish.duration spikes.")
            .publishPercentileHistogram()
            .register(meterRegistry);
    batchAttemptedSummary =
        DistributionSummary.builder("batch.outbox.forward.attempted")
            .description(
                "Events attempted per outbox forward round — sustained values near outbox.batchSize"
                    + " indicate saturation (backlog not draining).")
            .publishPercentileHistogram()
            .register(meterRegistry);
    batchSucceededSummary =
        DistributionSummary.builder("batch.outbox.forward.succeeded")
            .description(
                "Events successfully published per outbox forward round — gap vs attempted reflects"
                    + " Kafka-side failure/retry pressure.")
            .publishPercentileHistogram()
            .register(meterRegistry);
  }

  @Override
  @Timed(
      value = "batch.outbox.publish.duration",
      description = "Outbox batch publish latency including Kafka acknowledgements",
      histogram = true)
  @Retryable(
      // 注意:不重试 QueryTimeoutException。阶段一 markPublishing CAS 把状态推到 PUBLISHING,
      // 该状态不在 markPublishing 的可重入集合(NEW/FAILED);若阶段三 markPublished 超时触发重试,
      // 同事件已 PUBLISHING 会被跳过停留在中间态,等 resetStalePublishing 超时回收前消费方可能
      // 已处理。Kafka at-least-once + 重新触发会造成重复消费。超时让 resetStalePublishing 负责
      // 自愈即可,不在此处重试。
      retryFor = {CannotAcquireLockException.class, TransientDataAccessException.class},
      maxAttempts = 3,
      backoff = @Backoff(delay = 100, multiplier = 2, maxDelay = 1000))
  public ScheduleForwarderResult advance(SchedulePlan plan) {
    List<OutboxEventEntity> pendingEvents = pollPending(plan);
    // ── 阶段一：set-based markPublishing + 并行触发 Kafka 发送 ──────────────────
    List<InFlight> inFlight = claimAndPublish(pendingEvents);
    // ── 阶段二：等待本批所有 Kafka ACK（并行等待，耗时 ≈ 单条最长 RTT）────────
    awaitAcknowledgements(inFlight);
    // ── 阶段三：根据发送结果 set-based 批量更新 DB 状态 ─────────────────────────
    OutcomeGroups groups = classifyOutcomes(inFlight);
    Map<FailedGroupKey, Instant> nextRetryAtByGroup = flushOutcomeGroups(groups);
    recordPendingRetries(groups.pendingRetries(), nextRetryAtByGroup);
    // A6:本轮批填充规模喂 DistributionSummary(饱和/失败压力可观测)。
    batchAttemptedSummary.record(groups.attemptedEvents());
    batchSucceededSummary.record(groups.publishSucceeded());
    return ScheduleForwarderResult.of(
        groups.attemptedEvents(), groups.publishSucceeded(), groups.publishFailed());
  }

  /** 抢占成功、Kafka send 已发起的在途事件。 */
  private record InFlight(OutboxEventEntity event, CompletableFuture<Boolean> future) {}

  /** 阶段三失败组键：同租户同 attemptNo 的失败事件共用一条批量 UPDATE 与一次退避抽样。 */
  private record FailedGroupKey(String tenantId, int attemptNo) {}

  /** 阶段三之后待落 {@code event_outbox_retry} 审计的失败/GIVE_UP 事件。 */
  private record PendingRetry(OutboxEventEntity event, int attemptNo) {}

  /** 阶段三分类聚合结果：计数 + 按租户/失败组聚合的 id 集 + 待审计列表。 */
  private record OutcomeGroups(
      int attemptedEvents,
      int publishSucceeded,
      int publishFailed,
      Map<String, List<Long>> publishedByTenant,
      Map<String, List<Long>> giveUpByTenant,
      Map<FailedGroupKey, List<Long>> failedByGroup,
      List<PendingRetry> pendingRetries) {}

  private List<OutboxEventEntity> pollPending(SchedulePlan plan) {
    OutboxEventQuery pendingQuery =
        OutboxEventQuery.builder()
            .tenantId(plan == null ? null : plan.getTenantId())
            .pendingStatus1(OutboxPublishStatus.NEW.code())
            .pendingStatus2(OutboxPublishStatus.FAILED.code())
            .batchSize(governance.outbox().getBatchSize())
            .shardTotal(plan == null ? 1 : plan.getShardTotal())
            .shardIndex(plan == null ? 0 : plan.getShardIndex())
            .build();
    long pollStartNanos = System.nanoTime();
    List<OutboxEventEntity> pendingEvents = outboxEventMapper.selectPending(pendingQuery);
    pollTimer.record(Duration.ofNanos(System.nanoTime() - pollStartNanos));
    return pendingEvents;
  }

  /**
   * 阶段一：set-based 抢占 + 并行触发 Kafka 发送。
   *
   * <p>PERF(5.4): 抢占由逐条 CAS 改为按租户分组的批量 UPDATE ... RETURNING id(单条语句原子完成 NEW/FAILED→PUBLISHING
   * 抢占并返回胜出集,publish_attempt 递增语义不变)。selectPending 无租户过滤 时同批可能跨租户(shard 扫描),tenant_id
   * 是复合分布键,按租户分组保留分布键裁剪。
   */
  private List<InFlight> claimAndPublish(List<OutboxEventEntity> pendingEvents) {
    Map<String, List<Long>> pendingIdsByTenant = new LinkedHashMap<>();
    for (OutboxEventEntity event : pendingEvents) {
      pendingIdsByTenant
          .computeIfAbsent(event.getTenantId(), t -> new ArrayList<>())
          .add(event.getId());
    }
    Set<Long> wonIds = new HashSet<>();
    for (Map.Entry<String, List<Long>> entry : pendingIdsByTenant.entrySet()) {
      wonIds.addAll(
          outboxEventMapper.markPublishingBatch(
              entry.getKey(),
              entry.getValue(),
              OutboxPublishStatus.PUBLISHING.code(),
              OutboxPublishStatus.NEW.code(),
              OutboxPublishStatus.FAILED.code()));
    }
    List<InFlight> inFlight = new ArrayList<>(pendingEvents.size());
    for (OutboxEventEntity event : pendingEvents) {
      if (wonIds.contains(event.getId())) {
        CompletableFuture<Boolean> future;
        try {
          future = outboxPublisher.publish(event);
        } catch (RuntimeException exception) {
          future = CompletableFuture.completedFuture(false);
        }
        inFlight.add(
            new InFlight(
                event, future != null ? future : CompletableFuture.completedFuture(false)));
      }
    }
    return inFlight;
  }

  /**
   * 阶段二：等齐本批所有 Kafka ACK。
   *
   * <p>R7-A2-P0：用 exceptionally 捕获并抑制单条 future 的异常，否则 allOf.join() 抛 CompletionException 会让整个 try
   * 块上抛、阶段三完全跳过，整批事件原地停在 PUBLISHING 状态，要等 stale TTL (`resetStalePublishing`) 才能回收 — 实测会让 trigger
   * 高峰积压瞬间放大。阶段三里的 `future.getNow(false)` 已经对失败结果（false / 仍未完成）做正确处理，这里只负责等齐。
   */
  private void awaitAcknowledgements(List<InFlight> inFlight) {
    if (!inFlight.isEmpty()) {
      CompletableFuture.allOf(
              inFlight.stream().map(InFlight::future).toArray(CompletableFuture[]::new))
          .exceptionally(ex -> null)
          .join();
    }
  }

  /**
   * 阶段三(分类)：按发送结果把在途事件聚合为成功/失败/GIVE_UP 组。
   *
   * <p>PERF(5.4): 成功/失败/GIVE_UP 三组各按租户(失败组再按 attemptNo)聚合,组内一条 WHERE id IN (...) 批量 UPDATE;全部保留
   * R3-P0-6 的 publish_status='PUBLISHING' 守卫。
   */
  private OutcomeGroups classifyOutcomes(List<InFlight> inFlight) {
    int attemptedEvents = 0;
    int publishSucceeded = 0;
    int publishFailed = 0;
    Map<String, List<Long>> publishedByTenant = new LinkedHashMap<>();
    Map<String, List<Long>> giveUpByTenant = new LinkedHashMap<>();
    Map<FailedGroupKey, List<Long>> failedByGroup = new LinkedHashMap<>();
    List<PendingRetry> pendingRetries = new ArrayList<>();
    for (InFlight item : inFlight) {
      OutboxEventEntity event = item.event();
      attemptedEvents++;
      boolean published = Boolean.TRUE.equals(item.future().getNow(false));
      if (published) {
        publishSucceeded++;
        publishedByTenant
            .computeIfAbsent(event.getTenantId(), t -> new ArrayList<>())
            .add(event.getId());
        continue;
      }
      publishFailed++;
      int publishAttemptNo = event.getPublishAttempt() == null ? 1 : event.getPublishAttempt() + 1;
      if (publishAttemptNo >= governance.outbox().getMaxRetryAttempts()) {
        giveUpByTenant
            .computeIfAbsent(event.getTenantId(), t -> new ArrayList<>())
            .add(event.getId());
        giveUpCounter.increment();
      } else {
        failedByGroup
            .computeIfAbsent(
                new FailedGroupKey(event.getTenantId(), publishAttemptNo), key -> new ArrayList<>())
            .add(event.getId());
      }
      pendingRetries.add(new PendingRetry(event, publishAttemptNo));
    }
    return new OutcomeGroups(
        attemptedEvents,
        publishSucceeded,
        publishFailed,
        publishedByTenant,
        giveUpByTenant,
        failedByGroup,
        pendingRetries);
  }

  /**
   * 阶段三(回写)：成功/GIVE_UP 按租户、失败按 (租户, attemptNo) 各一条批量 UPDATE。 命中行数 != 入参集合大小时 log.warn(说明有行被并发方(如
   * resetStalePublishing)推走,守卫已正确拒写)。 失败组组内共用一个 computeNextRetryAt —— nextPublishAt 本就带随机
   * jitter,组内共享一个抽样与逐条各自抽样在语义上等价。
   *
   * @return 失败组 → 本轮抽样的 nextRetryAt(供审计记录复用)
   */
  private Map<FailedGroupKey, Instant> flushOutcomeGroups(OutcomeGroups groups) {
    for (Map.Entry<String, List<Long>> entry : groups.publishedByTenant().entrySet()) {
      int updated =
          outboxEventMapper.markPublishedBatch(
              entry.getKey(),
              entry.getValue(),
              OutboxPublishStatus.PUBLISHED.code(),
              OutboxPublishStatus.PUBLISHING.code());
      warnOnPartialUpdate("markPublished", entry.getKey(), entry.getValue().size(), updated);
    }
    for (Map.Entry<String, List<Long>> entry : groups.giveUpByTenant().entrySet()) {
      int updated =
          outboxEventMapper.markGiveUpBatch(
              entry.getKey(),
              entry.getValue(),
              OutboxPublishStatus.GIVE_UP.code(),
              OutboxPublishStatus.PUBLISHING.code());
      warnOnPartialUpdate("markGiveUp", entry.getKey(), entry.getValue().size(), updated);
    }
    Map<FailedGroupKey, Instant> nextRetryAtByGroup = new LinkedHashMap<>();
    for (Map.Entry<FailedGroupKey, List<Long>> entry : groups.failedByGroup().entrySet()) {
      Instant nextRetryAt = computeNextRetryAt(entry.getKey().attemptNo(), governance.outbox());
      nextRetryAtByGroup.put(entry.getKey(), nextRetryAt);
      int updated =
          outboxEventMapper.markFailedBatch(
              entry.getKey().tenantId(),
              entry.getValue(),
              OutboxPublishStatus.FAILED.code(),
              nextRetryAt,
              OutboxPublishStatus.PUBLISHING.code());
      warnOnPartialUpdate(
          "markFailed", entry.getKey().tenantId(), entry.getValue().size(), updated);
    }
    return nextRetryAtByGroup;
  }

  /** 阶段三(审计)：逐条落 {@code event_outbox_retry}(带 MDC;失败复用组抽样的 nextRetryAt,GIVE_UP 为 null)。 */
  private void recordPendingRetries(
      List<PendingRetry> pendingRetries, Map<FailedGroupKey, Instant> nextRetryAtByGroup) {
    for (PendingRetry retry : pendingRetries) {
      OutboxEventEntity event = retry.event();
      BatchMdc.put(StructuredLogField.TENANT_ID, event.getTenantId());
      BatchMdc.put(StructuredLogField.TRACE_ID, event.getTraceId());
      try {
        if (retry.attemptNo() >= governance.outbox().getMaxRetryAttempts()) {
          recordRetry(event, retry.attemptNo(), null, "retry attempts exhausted");
        } else {
          Instant groupNextRetryAt =
              nextRetryAtByGroup.get(new FailedGroupKey(event.getTenantId(), retry.attemptNo()));
          recordRetry(event, retry.attemptNo(), groupNextRetryAt, "publish failed");
        }
      } finally {
        BatchMdc.remove(StructuredLogField.TENANT_ID);
        BatchMdc.remove(StructuredLogField.TRACE_ID);
      }
    }
  }

  /** PERF(5.4): 批量 UPDATE 命中行数与入参集合大小不一致时告警（守卫拒写=并发方已推进该行）。 */
  private void warnOnPartialUpdate(String action, String tenantId, int expected, int updated) {
    if (updated != expected) {
      log.warn(
          "outbox batch {} partial update: tenant={} expected={} updated={} — rows guarded by"
              + " publish_status='PUBLISHING' were advanced concurrently",
          action,
          tenantId,
          expected,
          updated);
    }
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
