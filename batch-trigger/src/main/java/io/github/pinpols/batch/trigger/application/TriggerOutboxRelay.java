package io.github.pinpols.batch.trigger.application;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.persistence.entity.TriggerOutboxEventEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;
import io.github.pinpols.batch.trigger.mapper.TriggerOutboxEventMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * ADR-010 §Stage 2: trigger_outbox_event 周期发布器。
 *
 * <p>循环步骤(每轮持 ShedLock 串行,多 trigger 实例间互斥):
 *
 * <ol>
 *   <li>{@code selectPending} 拿一批 NEW/FAILED 行(FOR UPDATE SKIP LOCKED 防多实例重发)
 *   <li>一条 {@code markPublishingBatch ... RETURNING} 批量 CAS 抢占
 *   <li>反序列化 payload → {@link TriggerEventPublisher#publishAsync} 发起整批 Kafka 投递
 *   <li>等齐 ACK 后一条 {@code markPublishedBatch} 回写成功；失败事件按原退避规则单独更新
 * </ol>
 *
 * <p>退避策略:失败时 {@code next_publish_at = now + min(60s, 2^attempt 秒)}。
 *
 * <p>ADR-010 固化路径，无条件启用（2026-05-02 同步 HTTP 路径已删除）。
 *
 * <p>简化设计(对比 orchestrator 的 OutboxPollScheduler):
 *
 * <ul>
 *   <li>无 Circuit Breaker:trigger 流量小,发布失败靠退避吸收即可
 *   <li>无 Sharding:单 trigger leader 模式(ShedLock 互斥);trigger 不像 orchestrator 需要分片扩容
 *   <li>无自适应轮询:固定间隔(可配),业务量小不必精细化
 *   <li>每轮重置 stale PUBLISHING:避免 relay 在 markPublishing 后崩溃导致行永久卡住
 * </ul>
 */
@Component
@Slf4j
public class TriggerOutboxRelay {

  private static final Duration LOCK_AT_MOST = Duration.ofMinutes(1);

  /** 退避上限,单条失败后最长 60s 后重试(2^6 = 64 → 60s 截断)。 */
  private static final long MAX_BACKOFF_SECONDS = 60L;

  private final TriggerOutboxEventMapper mapper;
  private final TriggerEventPublisher publisher;
  private final LockingTaskExecutor lockingTaskExecutor;
  private final MeterRegistry meterRegistry;
  private final TriggerOutboxRelayProperties properties;
  private final ThreadPoolTaskScheduler scheduler;
  private final TriggerOutboxReleaseBudget releaseBudget;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong pendingEvents = new AtomicLong();
  private final AtomicLong stalePublishingEvents = new AtomicLong();
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean stopping = new AtomicBoolean(false);
  private final AtomicReference<ScheduledFuture<?>> scheduledTask = new AtomicReference<>();
  private Counter giveUpCounter;
  private Counter releaseBudgetExhaustedCounter;

  public TriggerOutboxRelay(
      TriggerOutboxEventMapper mapper,
      TriggerEventPublisher publisher,
      LockingTaskExecutor lockingTaskExecutor,
      MeterRegistry meterRegistry,
      TriggerOutboxRelayProperties properties,
      @Qualifier("triggerOutboxRelayScheduler") ThreadPoolTaskScheduler scheduler) {
    this.mapper = mapper;
    this.publisher = publisher;
    this.lockingTaskExecutor = lockingTaskExecutor;
    this.meterRegistry = meterRegistry;
    this.properties = properties;
    this.scheduler = scheduler;
    this.releaseBudget = new TriggerOutboxReleaseBudget(properties);
  }

  // R3-P1-3：单条 outbox 事件 NEW→PUBLISHED 端到端延迟分位，按 result tag (ok/fail) 拆分。
  // 之前只有积压 gauge，无法区分 Kafka 慢 vs relay 调度慢。
  private io.micrometer.core.instrument.Timer publishLatencyOk;
  private io.micrometer.core.instrument.Timer publishLatencyFail;

  /**
   * R3-P1-9：从 {@code @PostConstruct} 改为 {@code @EventListener(ApplicationReadyEvent.class)} — 之前
   * PostConstruct 阶段 Flyway 迁移可能未完成， 第一轮 poll 访问 {@code trigger_outbox_event} 表的新列会抛 schema 错误（被吞为
   * noise）。
   *
   * <p>P0 修复：把原 {@code auditOnReady} 合并进 {@code start()},避免两个独立 ApplicationReadyEvent 监听器并发 /
   * 顺序不确定导致的 TOCTOU 与重复 metrics 注册。
   *
   * <p>P0 修复：调度由 Spring 托管 {@link ThreadPoolTaskScheduler} 接管,替代原自建 {@code
   * Executors.newSingleThreadScheduledExecutor}(unbounded queue + 游离生命周期 + 无 Actuator)。
   */
  @EventListener(ApplicationReadyEvent.class)
  public synchronized void start() {
    if (!started.compareAndSet(false, true)) {
      return; // 已启动（防 dev tools 重启 / 重复事件场景）
    }
    meterRegistry.gauge("batch.trigger.outbox.pending.events", pendingEvents);
    meterRegistry.gauge("batch.trigger.outbox.publishing.stale.events", stalePublishingEvents);
    giveUpCounter = Counter.builder("batch.trigger.outbox.give_up.total")
        .description("trigger_outbox_event rows transitioned to GIVE_UP")
        .register(meterRegistry);
    releaseBudgetExhaustedCounter = Counter.builder("batch.trigger.outbox.release_budget.exhausted")
        .description(
            "Trigger outbox relay polls deferred because the per-second release budget was exhausted")
        .register(meterRegistry);
    meterRegistry.gauge(
        "batch.trigger.outbox.release_budget.reserved",
        releaseBudget,
        TriggerOutboxReleaseBudget::reservedInCurrentWindow);
    meterRegistry.gauge(
        "batch.trigger.outbox.release_budget.limit",
        properties,
        TriggerOutboxRelayProperties::getMaxPublishEventsPerSecond);
    publishLatencyOk = io.micrometer.core.instrument.Timer.builder(
            "batch.trigger.outbox.publish.latency")
        .description("trigger_outbox publishOne latency (single event)")
        .tags("result", "ok")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(meterRegistry);
    publishLatencyFail = io.micrometer.core.instrument.Timer.builder(
            "batch.trigger.outbox.publish.latency")
        .description("trigger_outbox publishOne latency (single event)")
        .tags("result", "fail")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(meterRegistry);
    scheduledTask.set(scheduler.scheduleWithFixedDelay(
        this::poll, Duration.ofMillis(properties.getPollIntervalMillis())));
    log.info(
        "TriggerOutboxRelay started: poll={}ms batch={} release_budget={}events/s backoff_max={}s",
        properties.getPollIntervalMillis(),
        properties.getBatchSize(),
        properties.getMaxPublishEventsPerSecond(),
        MAX_BACKOFF_SECONDS);
    // 启动末尾顺手跑一次运行态审计(原 auditOnReady 监听器合并到这里,串行,无 TOCTOU)
    runStartupAudit();
  }

  @EventListener(ContextClosedEvent.class)
  public void stopOnContextClosed(ContextClosedEvent event) {
    if (!stopping.compareAndSet(false, true)) {
      return;
    }
    ScheduledFuture<?> task = scheduledTask.getAndSet(null);
    if (EmptyChecks.isNotNull(task)) {
      task.cancel(true);
    }
    log.info("TriggerOutboxRelay stopping: cancelled scheduled polling");
  }

  private void runStartupAudit() {
    try {
      long pending = mapper.countByStatuses(
          List.of(OutboxPublishStatus.NEW.code(), OutboxPublishStatus.FAILED.code()));
      long stale = mapper.countStalePublishing(
          OutboxPublishStatus.PUBLISHING.code(), properties.getPublishingTimeoutSeconds());
      pendingEvents.set(pending);
      stalePublishingEvents.set(stale);
      if (pending == 0 && stale == 0) {
        log.info(
            "Startup runtime audit passed (trigger): no pending or stale PUBLISHING trigger_outbox events");
      } else {
        log.warn(
            "Startup runtime audit found residual events (trigger): triggerOutboxPending={},"
                + " triggerOutboxStalePublishing={}; this audit only reports the condition."
                + " The first TriggerOutboxRelay stale-reset/publish cycle will recover it.",
            pending,
            stale);
      }
    } catch (RuntimeException ex) {
      log.warn("Startup runtime audit failed (trigger; startup continues): {}", ex.getMessage());
    }
  }

  /** 单元测试可直接调用本方法跑一轮(不走自调度循环)。 */
  public void poll() {
    if (stopping.get()) {
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      if (stopping.get()) {
        return;
      }
      lockingTaskExecutor.executeWithLock((Runnable) this::pollLocked, lockConfig());
    } catch (DataAccessException dae) {
      log.warn(
          "TriggerOutboxRelay transient DB failure; retrying on the next cycle: {}",
          EmptyChecks.isNull(dae.getMostSpecificCause())
              ? dae.getMessage()
              : dae.getMostSpecificCause().getMessage());
    } catch (Exception t) {
      if (stopping.get() && isRedisStopping(t)) {
        log.info("TriggerOutboxRelay poll skipped during shutdown: {}", t.getMessage());
        return;
      }
      log.error("TriggerOutboxRelay failed", t);
    } finally {
      running.set(false);
    }
  }

  private static boolean isRedisStopping(Throwable throwable) {
    Throwable current = throwable;
    while (EmptyChecks.isNotNull(current)) {
      String message = current.getMessage();
      if (EmptyChecks.isNotNull(message)
          && message.contains("LettuceConnectionFactory is STOPPING")) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private void pollLocked() {
    if (shouldStopPolling()) {
      return;
    }
    resetStalePublishing();
    if (shouldStopPolling()) {
      return;
    }
    sampleBacklog();
    if (shouldStopPolling()) {
      return;
    }
    Instant now = BatchDateTimeSupport.utcNow();
    int permittedBatchSize =
        releaseBudget.reserveAtPace(properties.getBatchSize(), properties.getPollIntervalMillis());
    if (permittedBatchSize == 0) {
      if (releaseBudgetExhaustedCounter != null) {
        releaseBudgetExhaustedCounter.increment();
      }
      return;
    }
    List<TriggerOutboxEventEntity> batch = mapper.selectPending(
        now, permittedBatchSize, OutboxPublishStatus.NEW.code(), OutboxPublishStatus.FAILED.code());
    if (EmptyChecks.isEmpty(batch)) {
      return;
    }
    log.debug("TriggerOutboxRelay loaded {} pending events", batch.size());
    publishBatch(batch);
  }

  /** 已抢占且已发起 Kafka send 的事件；future 完成即表示 broker ACK 或明确失败。 */
  private record InFlight(
      TriggerOutboxEventEntity event,
      CompletableFuture<TriggerEventPublisher.PublishResult> future,
      long startedNanos) {}

  /**
   * 三阶段批处理：批量抢占、并发投递、按结果回写。
   *
   * <p>不能在 Kafka callback 线程中直接更新 DB：一是 callback 线程会被慢 SQL 拖住，二是无法把同批成功事件合并为一条 UPDATE。
   * relay 线程只在所有 ACK 完成后做一次成功回写；Kafka 成功但 JVM 在回写前崩溃仍会重投，维持 outbox 的至少一次语义。
   */
  private void publishBatch(List<TriggerOutboxEventEntity> batch) {
    List<Long> pendingIds = batch.stream().map(TriggerOutboxEventEntity::getId).toList();
    List<Long> claimed = mapper.markPublishingBatch(
        pendingIds,
        OutboxPublishStatus.PUBLISHING.code(),
        OutboxPublishStatus.NEW.code(),
        OutboxPublishStatus.FAILED.code());
    if (EmptyChecks.isEmpty(claimed)) {
      return;
    }
    Set<Long> claimedIds = new HashSet<>(claimed);

    List<InFlight> inFlight = new ArrayList<>(claimedIds.size());
    for (TriggerOutboxEventEntity event : batch) {
      if (!claimedIds.contains(event.getId())) {
        continue;
      }
      long startedNanos = System.nanoTime();
      LaunchEnvelope envelope = deserializeOrGiveUp(event, startedNanos);
      if (EmptyChecks.isNull(envelope)) {
        continue;
      }
      String messageKey = event.getTenantId() + ":" + event.getRequestId();
      CompletableFuture<TriggerEventPublisher.PublishResult> future;
      try {
        future = publisher.publishAsync(event.getTopic(), messageKey, envelope, event.getTraceId());
      } catch (RuntimeException ex) {
        log.error(
            "TriggerOutboxRelay failed to initiate Kafka publish: id={} tenantId={} requestId={}",
            event.getId(),
            event.getTenantId(),
            event.getRequestId(),
            ex);
        future = CompletableFuture.completedFuture(
            TriggerEventPublisher.PublishResult.fail("kafka send init: " + ex.getMessage()));
      }
      inFlight.add(new InFlight(
          event,
          EmptyChecks.isNotNull(future)
              ? future
              : CompletableFuture.completedFuture(
                  TriggerEventPublisher.PublishResult.fail("null publish future")),
          startedNanos));
    }

    awaitAcknowledgements(inFlight);
    flushOutcomes(inFlight);
  }

  private LaunchEnvelope deserializeOrGiveUp(TriggerOutboxEventEntity event, long startedNanos) {
    try {
      return JsonUtils.fromJson(event.getPayload(), LaunchEnvelope.class);
    } catch (IllegalArgumentException ex) {
      log.error(
          "TriggerOutboxRelay failed to deserialize payload; marking GIVE_UP: id={} requestId={}",
          event.getId(),
          event.getRequestId(),
          ex);
      markGiveUp(event, "payload deserialize: " + ex.getMessage());
      recordPublishLatency(false, startedNanos);
      return null;
    }
  }

  private void awaitAcknowledgements(List<InFlight> inFlight) {
    if (EmptyChecks.isNotEmpty(inFlight)) {
      CompletableFuture.allOf(
              inFlight.stream().map(InFlight::future).toArray(CompletableFuture[]::new))
          // 单条 future 异常时也必须进入 flushOutcomes，否则整批卡 PUBLISHING 等 stale 回收。
          .orTimeout(properties.getPublishingTimeoutSeconds(), TimeUnit.SECONDS)
          .exceptionally(ignored -> null)
          .join();
    }
  }

  private void flushOutcomes(List<InFlight> inFlight) {
    List<Long> publishedIds = new ArrayList<>();
    for (InFlight item : inFlight) {
      TriggerEventPublisher.PublishResult result = completedResult(item.future());
      boolean success = result.success();
      recordPublishLatency(success, item.startedNanos());
      if (success) {
        publishedIds.add(item.event().getId());
      } else {
        markPublishFailure(item.event(), result.errorMessage());
      }
    }
    if (EmptyChecks.isNotEmpty(publishedIds)) {
      int updated = mapper.markPublishedBatch(
          publishedIds,
          OutboxPublishStatus.PUBLISHED.code(),
          OutboxPublishStatus.PUBLISHING.code());
      if (updated != publishedIds.size()) {
        log.warn(
            "TriggerOutboxRelay markPublishedBatch partial update: expected={} updated={}",
            publishedIds.size(),
            updated);
      }
    }
  }

  private static TriggerEventPublisher.PublishResult completedResult(
      CompletableFuture<TriggerEventPublisher.PublishResult> future) {
    if (!future.isDone() || future.isCancelled() || future.isCompletedExceptionally()) {
      return TriggerEventPublisher.PublishResult.fail("kafka publish future failed");
    }
    TriggerEventPublisher.PublishResult result = future.getNow(null);
    return EmptyChecks.isNotNull(result)
        ? result
        : TriggerEventPublisher.PublishResult.fail("null publish result");
  }

  private void recordPublishLatency(boolean success, long startedNanos) {
    if (EmptyChecks.isNotNull(publishLatencyOk)) {
      (success ? publishLatencyOk : publishLatencyFail)
          .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }
  }

  private void markPublishFailure(TriggerOutboxEventEntity event, String errorMessage) {
    int nextAttempt = event.getPublishAttempt() + 1;
    if (nextAttempt >= Math.max(1, properties.getMaxPublishAttempts())) {
      log.error(
          "TriggerOutboxRelay GIVE_UP after {} attempts: id={} requestId={} topic={} error={}",
          nextAttempt,
          event.getId(),
          event.getRequestId(),
          event.getTopic(),
          errorMessage);
      markGiveUp(event, errorMessage);
      return;
    }
    Instant retryAt = BatchDateTimeSupport.utcNow().plusSeconds(backoffSeconds(nextAttempt));
    int updated = mapper.markFailed(
        event.getId(),
        OutboxPublishStatus.FAILED.code(),
        truncate(errorMessage),
        retryAt,
        OutboxPublishStatus.PUBLISHING.code());
    if (updated == 0) {
      log.warn(
          "TriggerOutboxRelay markFailed(FAILED) affected 0 rows; another instance took over the event: id={}",
          event.getId());
    }
  }

  private void markGiveUp(TriggerOutboxEventEntity event, String errorMessage) {
    int updated = mapper.markFailed(
        event.getId(),
        OutboxPublishStatus.GIVE_UP.code(),
        truncate(errorMessage),
        BatchDateTimeSupport.utcNow().plusSeconds(MAX_BACKOFF_SECONDS),
        OutboxPublishStatus.PUBLISHING.code());
    if (updated == 0) {
      log.warn(
          "TriggerOutboxRelay markFailed(GIVE_UP) affected 0 rows; another instance took over the event: id={}",
          event.getId());
    }
    if (EmptyChecks.isNotNull(giveUpCounter)) {
      giveUpCounter.increment();
    }
  }

  private boolean shouldStopPolling() {
    return stopping.get() || Thread.currentThread().isInterrupted();
  }

  /** 指数退避:attempt=1→2s, 2→4s, 3→8s, ..., 上限 60s。 */
  static long backoffSeconds(int attempt) {
    if (attempt <= 0) {
      return 1L;
    }
    long shift = Math.min(attempt, 6); // 2^6 = 64 → 截断到 60
    long backoff = 1L << shift;
    return Math.min(backoff, MAX_BACKOFF_SECONDS);
  }

  private static String truncate(String s) {
    if (EmptyChecks.isNull(s)) {
      return null;
    }
    return s.length() <= 2000 ? s : s.substring(0, 2000);
  }

  private void resetStalePublishing() {
    int reset = mapper.resetStalePublishing(
        OutboxPublishStatus.PUBLISHING.code(),
        OutboxPublishStatus.FAILED.code(),
        "stale PUBLISHING reset by TriggerOutboxRelay",
        properties.getPublishingTimeoutSeconds());
    if (reset > 0) {
      log.warn("TriggerOutboxRelay reset {} stale PUBLISHING events to FAILED", reset);
    }
  }

  private void sampleBacklog() {
    pendingEvents.set(mapper.countByStatuses(
        List.of(OutboxPublishStatus.NEW.code(), OutboxPublishStatus.FAILED.code())));
    stalePublishingEvents.set(mapper.countStalePublishing(
        OutboxPublishStatus.PUBLISHING.code(), properties.getPublishingTimeoutSeconds()));
  }

  private LockConfiguration lockConfig() {
    return new LockConfiguration(
        BatchDateTimeSupport.utcNow(),
        "trigger_outbox_relay",
        LOCK_AT_MOST,
        Duration.ofMillis(properties.getPollIntervalMillis()));
  }
}
