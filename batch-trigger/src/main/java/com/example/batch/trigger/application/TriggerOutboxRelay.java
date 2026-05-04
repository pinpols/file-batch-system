package com.example.batch.trigger.application;

import com.example.batch.common.dto.LaunchEnvelope;
import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.persistence.entity.TriggerOutboxEventEntity;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.trigger.mapper.TriggerOutboxEventMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
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
@RequiredArgsConstructor
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

  @Value("${batch.trigger.outbox.poll-interval-millis:200}")
  private long pollIntervalMillis = 200L;

  @Value("${batch.trigger.outbox.batch-size:100}")
  private int batchSize = 100;

  @Value("${batch.trigger.outbox.publishing-timeout-seconds:120}")
  private long publishingTimeoutSeconds = 120L;

  @Value("${batch.trigger.outbox.max-publish-attempts:10}")
  private int maxPublishAttempts = 10;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong pendingEvents = new AtomicLong();
  private final AtomicLong stalePublishingEvents = new AtomicLong();
  private ScheduledExecutorService executor;
  private Counter giveUpCounter;

  @PostConstruct
  public void start() {
    meterRegistry.gauge("batch.trigger.outbox.pending.events", pendingEvents);
    meterRegistry.gauge("batch.trigger.outbox.publishing.stale.events", stalePublishingEvents);
    giveUpCounter =
        Counter.builder("batch.trigger.outbox.give_up.total")
            .description("trigger_outbox_event rows transitioned to GIVE_UP")
            .register(meterRegistry);
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "trigger-outbox-relay");
              t.setDaemon(true);
              return t;
            });
    executor.scheduleWithFixedDelay(
        this::poll, pollIntervalMillis, pollIntervalMillis, TimeUnit.MILLISECONDS);
    log.info(
        "TriggerOutboxRelay 已启动:poll={}ms batch={} backoff_max={}s",
        pollIntervalMillis,
        batchSize,
        MAX_BACKOFF_SECONDS);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void auditOnReady() {
    try {
      long pending =
          mapper.countByStatuses(
              List.of(OutboxPublishStatus.NEW.code(), OutboxPublishStatus.FAILED.code()));
      long stale =
          mapper.countStalePublishing(
              OutboxPublishStatus.PUBLISHING.code(), publishingTimeoutSeconds);
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

  @PreDestroy
  public void stop() {
    if (executor == null) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
        log.warn("TriggerOutboxRelay 未在 15s 内完成关闭,强制中断");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
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
    Instant now = Instant.now();
    List<TriggerOutboxEventEntity> batch =
        mapper.selectPending(
            now, batchSize, OutboxPublishStatus.NEW.code(), OutboxPublishStatus.FAILED.code());
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
    int claimed =
        mapper.markPublishing(
            event.getId(),
            OutboxPublishStatus.PUBLISHING.code(),
            OutboxPublishStatus.NEW.code(),
            OutboxPublishStatus.FAILED.code());
    if (claimed == 0) {
      // 已被其它 relay 实例 / 之前的 hung-process 抢走或状态已变,本轮跳过
      return;
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
          Instant.now().plusSeconds(MAX_BACKOFF_SECONDS));
      if (giveUpCounter != null) {
        giveUpCounter.increment();
      }
      return;
    }
    String messageKey = event.getTenantId() + ":" + event.getRequestId();
    TriggerEventPublisher.PublishResult result =
        publisher.publish(event.getTopic(), messageKey, envelope, event.getTraceId());
    if (result.success()) {
      mapper.markPublished(event.getId(), OutboxPublishStatus.PUBLISHED.code());
    } else {
      int nextAttempt = event.getPublishAttempt() + 1;
      if (nextAttempt >= Math.max(1, maxPublishAttempts)) {
        mapper.markFailed(
            event.getId(),
            OutboxPublishStatus.GIVE_UP.code(),
            truncate(result.errorMessage()),
            Instant.now().plusSeconds(MAX_BACKOFF_SECONDS));
        if (giveUpCounter != null) {
          giveUpCounter.increment();
        }
        return;
      }
      Instant retryAt = Instant.now().plusSeconds(backoffSeconds(nextAttempt));
      mapper.markFailed(
          event.getId(),
          OutboxPublishStatus.FAILED.code(),
          truncate(result.errorMessage()),
          retryAt);
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
            publishingTimeoutSeconds);
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
            OutboxPublishStatus.PUBLISHING.code(), publishingTimeoutSeconds));
  }

  private LockConfiguration lockConfig() {
    return new LockConfiguration(
        Instant.now(), "trigger_outbox_relay", LOCK_AT_MOST, LOCK_AT_LEAST);
  }
}
