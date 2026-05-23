package com.example.batch.trigger.application;

import com.example.batch.common.dto.LaunchEnvelope;
import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.persistence.entity.TriggerOutboxEventEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.trigger.config.TriggerOutboxRelayProperties;
import com.example.batch.trigger.mapper.TriggerOutboxEventMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
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
 *   <li>{@code selectPending} 拿一批 PENDING/FAILED 行(FOR UPDATE SKIP LOCKED 防多实例重发)
 *   <li>逐行 {@code markPublishing} 抢占(CAS 失败跳过)
 *   <li>反序列化 payload → {@link TriggerEventPublisher#publish} 同步发到 Kafka
 *   <li>成功 → {@code markPublished};失败 → {@code markFailed} 递增 attempt + 退避
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
  private static final Duration LOCK_AT_LEAST = Duration.ofSeconds(2);

  /** 退避上限,单条失败后最长 60s 后重试(2^6 = 64 → 60s 截断)。 */
  private static final long MAX_BACKOFF_SECONDS = 60L;

  private final TriggerOutboxEventMapper mapper;
  private final TriggerEventPublisher publisher;
  private final LockingTaskExecutor lockingTaskExecutor;
  private final MeterRegistry meterRegistry;
  private final TriggerOutboxRelayProperties properties;
  private final ThreadPoolTaskScheduler scheduler;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong pendingEvents = new AtomicLong();
  private final AtomicLong stalePublishingEvents = new AtomicLong();
  private final AtomicBoolean started = new AtomicBoolean(false);
  private Counter giveUpCounter;

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
  }
  // R3-P1-3：单条 outbox 事件 NEW→PUBLISHED 端到端延迟分位，按 result tag (ok/fail) 拆分。
  // 之前只有积压 gauge，无法区分 Kafka 慢 vs relay 调度慢。
  private io.micrometer.core.instrument.Timer publishLatencyOk;
  private io.micrometer.core.instrument.Timer publishLatencyFail;

  /**
   * R3-P1-9：从 {@code @PostConstruct} 改为 {@code @EventListener(ApplicationReadyEvent.class)} —
   * 之前 PostConstruct 阶段 Flyway 迁移可能未完成， 第一轮 poll 访问 {@code trigger_outbox_event}
   * 表的新列会抛 schema 错误（被吞为 noise）。
   *
   * <p>P0 修复：把原 {@code auditOnReady} 合并进 {@code start()},避免两个独立 ApplicationReadyEvent
   * 监听器并发 / 顺序不确定导致的 TOCTOU 与重复 metrics 注册。
   *
   * <p>P0 修复：调度由 Spring 托管 {@link ThreadPoolTaskScheduler} 接管,替代原自建
   * {@code Executors.newSingleThreadScheduledExecutor}(unbounded queue + 游离生命周期 + 无 Actuator)。
   */
  @EventListener(ApplicationReadyEvent.class)
  public synchronized void start() {
    if (!started.compareAndSet(false, true)) {
      return; // 已启动（防 dev tools 重启 / 重复事件场景）
    }
    meterRegistry.gauge("batch.trigger.outbox.pending.events", pendingEvents);
    meterRegistry.gauge("batch.trigger.outbox.publishing.stale.events", stalePublishingEvents);
    giveUpCounter =
        Counter.builder("batch.trigger.outbox.give_up.total")
            .description("trigger_outbox_event rows transitioned to GIVE_UP")
            .register(meterRegistry);
    publishLatencyOk =
        io.micrometer.core.instrument.Timer.builder("batch.trigger.outbox.publish.latency")
            .description("trigger_outbox publishOne latency (single event)")
            .tags("result", "ok")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
    publishLatencyFail =
        io.micrometer.core.instrument.Timer.builder("batch.trigger.outbox.publish.latency")
            .description("trigger_outbox publishOne latency (single event)")
            .tags("result", "fail")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
    scheduler.scheduleWithFixedDelay(
        this::poll, Duration.ofMillis(properties.getPollIntervalMillis()));
    log.info(
        "TriggerOutboxRelay 已启动:poll={}ms batch={} backoff_max={}s",
        properties.getPollIntervalMillis(),
        properties.getBatchSize(),
        MAX_BACKOFF_SECONDS);
    // 启动末尾顺手跑一次运行态审计(原 auditOnReady 监听器合并到这里,串行,无 TOCTOU)
    runStartupAudit();
  }

  private void runStartupAudit() {
    try {
      long pending =
          mapper.countByStatuses(
              List.of(OutboxPublishStatus.NEW.code(), OutboxPublishStatus.FAILED.code()));
      long stale =
          mapper.countStalePublishing(
              OutboxPublishStatus.PUBLISHING.code(), properties.getPublishingTimeoutSeconds());
      pendingEvents.set(pending);
      stalePublishingEvents.set(stale);
      if (pending == 0 && stale == 0) {
        log.info("启动运行态审计通过（trigger）：trigger_outbox 无待发积压 / stale PUBLISHING");
      } else {
        log.warn(
            "启动运行态审计发现残留（trigger）：triggerOutboxPending={},"
                + " triggerOutboxStalePublishing={}—— 本次审计仅告警，修复交给"
                + " TriggerOutboxRelay 第一轮 stale reset / publish 自动完成。",
            pending,
            stale);
      }
    } catch (RuntimeException ex) {
      log.warn("启动运行态审计执行失败（trigger，不影响启动）：{}", ex.getMessage());
    }
  }

  /** 单元测试可直接调用本方法跑一轮(不走自调度循环)。 */
  public void poll() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      lockingTaskExecutor.executeWithLock(
          (LockingTaskExecutor.Task) this::pollLocked, lockConfig());
    } catch (DataAccessException dae) {
      log.warn(
          "TriggerOutboxRelay DB 瞬时异常,下轮重试: {}",
          dae.getMostSpecificCause() == null
              ? dae.getMessage()
              : dae.getMostSpecificCause().getMessage());
    } catch (Throwable t) {
      log.error("TriggerOutboxRelay 异常", t);
    } finally {
      running.set(false);
    }
  }

  private void pollLocked() {
    resetStalePublishing();
    sampleBacklog();
    Instant now = BatchDateTimeSupport.utcNow();
    List<TriggerOutboxEventEntity> batch =
        mapper.selectPending(
            now,
            properties.getBatchSize(),
            OutboxPublishStatus.NEW.code(),
            OutboxPublishStatus.FAILED.code());
    if (batch.isEmpty()) {
      return;
    }
    log.debug("TriggerOutboxRelay 本轮取 {} 条待发", batch.size());
    for (TriggerOutboxEventEntity event : batch) {
      try {
        publishOne(event);
      } catch (Throwable t) {
        // 单条异常不能拖累整批;失败已写库,异常本身只为 ERROR 日志
        log.error(
            "TriggerOutboxRelay 单条投递异常: id={} tenantId={} requestId={}",
            event.getId(),
            event.getTenantId(),
            event.getRequestId(),
            t);
      }
    }
  }

  private void publishOne(TriggerOutboxEventEntity event) {
    long startNanos = System.nanoTime();
    boolean ok = false;
    try {
      ok = publishOneInternal(event);
    } finally {
      if (publishLatencyOk != null) {
        io.micrometer.core.instrument.Timer timer = ok ? publishLatencyOk : publishLatencyFail;
        timer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
      }
    }
  }

  /** R3-P1-3: 主逻辑提取，外层 publishOne 负责耗时记录 */
  private boolean publishOneInternal(TriggerOutboxEventEntity event) {
    int claimed =
        mapper.markPublishing(
            event.getId(),
            OutboxPublishStatus.PUBLISHING.code(),
            OutboxPublishStatus.NEW.code(),
            OutboxPublishStatus.FAILED.code());
    if (claimed == 0) {
      // 已被其它 relay 实例 / 之前的 hung-process 抢走或状态已变,本轮跳过
      return false;
    }
    LaunchEnvelope envelope;
    try {
      envelope = JsonUtils.fromJson(event.getPayload(), LaunchEnvelope.class);
    } catch (IllegalArgumentException ex) {
      // payload 反序列化失败 = 数据问题,不可能靠重试解决,直接 GIVE_UP
      log.error(
          "TriggerOutboxRelay 反序列化 payload 失败,标 GIVE_UP: id={} requestId={}",
          event.getId(),
          event.getRequestId(),
          ex);
      mapper.markFailed(
          event.getId(),
          OutboxPublishStatus.GIVE_UP.code(),
          truncate("payload deserialize: " + ex.getMessage()),
          BatchDateTimeSupport.utcNow().plusSeconds(MAX_BACKOFF_SECONDS));
      if (giveUpCounter != null) {
        giveUpCounter.increment();
      }
      return false;
    }
    String messageKey = event.getTenantId() + ":" + event.getRequestId();
    TriggerEventPublisher.PublishResult result =
        publisher.publish(event.getTopic(), messageKey, envelope, event.getTraceId());
    if (result.success()) {
      mapper.markPublished(event.getId(), OutboxPublishStatus.PUBLISHED.code());
      return true;
    } else {
      int nextAttempt = event.getPublishAttempt() + 1;
      if (nextAttempt >= Math.max(1, properties.getMaxPublishAttempts())) {
        // P1-6 (pre-launch audit 2026-05-18)：GIVE_UP = 调度请求永久丢失,P0 级业务损失。
        // 原来只有 counter 被动监控,补 ERROR 让 oncall 日志告警直接命中。
        // 告警规则: increase(batch_trigger_outbox_give_up_total[5m]) > 0
        log.error(
            "TriggerOutboxRelay GIVE_UP after {} attempts: id={} requestId={} topic={} error={}",
            nextAttempt,
            event.getId(),
            event.getRequestId(),
            event.getTopic(),
            result.errorMessage());
        mapper.markFailed(
            event.getId(),
            OutboxPublishStatus.GIVE_UP.code(),
            truncate(result.errorMessage()),
            BatchDateTimeSupport.utcNow().plusSeconds(MAX_BACKOFF_SECONDS));
        if (giveUpCounter != null) {
          giveUpCounter.increment();
        }
        return false;
      }
      Instant retryAt = BatchDateTimeSupport.utcNow().plusSeconds(backoffSeconds(nextAttempt));
      mapper.markFailed(
          event.getId(),
          OutboxPublishStatus.FAILED.code(),
          truncate(result.errorMessage()),
          retryAt);
      return false;
    }
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
    if (s == null) {
      return null;
    }
    return s.length() <= 2000 ? s : s.substring(0, 2000);
  }

  private void resetStalePublishing() {
    int reset =
        mapper.resetStalePublishing(
            OutboxPublishStatus.PUBLISHING.code(),
            OutboxPublishStatus.FAILED.code(),
            "stale PUBLISHING reset by TriggerOutboxRelay",
            properties.getPublishingTimeoutSeconds());
    if (reset > 0) {
      log.warn("TriggerOutboxRelay 重置 {} 条滞留 PUBLISHING 为 FAILED", reset);
    }
  }

  private void sampleBacklog() {
    pendingEvents.set(
        mapper.countByStatuses(
            List.of(OutboxPublishStatus.NEW.code(), OutboxPublishStatus.FAILED.code())));
    stalePublishingEvents.set(
        mapper.countStalePublishing(
            OutboxPublishStatus.PUBLISHING.code(), properties.getPublishingTimeoutSeconds()));
  }

  private LockConfiguration lockConfig() {
    return new LockConfiguration(
        BatchDateTimeSupport.utcNow(), "trigger_outbox_relay", LOCK_AT_MOST, LOCK_AT_LEAST);
  }
}
