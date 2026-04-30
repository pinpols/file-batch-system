package com.example.batch.trigger.application;

import com.example.batch.common.dto.LaunchEnvelope;
import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.persistence.entity.TriggerOutboxEventEntity;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.trigger.mapper.TriggerOutboxEventMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <p>条件启用:仅在 {@code batch.trigger.async-launch.enabled=true} 时实例化;默认关闭,trigger 走原同步 HTTP 路径(由
 * {@code DefaultTriggerService} 控制写不写 outbox)。
 *
 * <p>简化设计(对比 orchestrator 的 OutboxPollScheduler):
 *
 * <ul>
 *   <li>无 Circuit Breaker:trigger 流量小,发布失败靠退避吸收即可
 *   <li>无 Sharding:单 trigger leader 模式(ShedLock 互斥);trigger 不像 orchestrator 需要分片扩容
 *   <li>无自适应轮询:固定间隔(可配),业务量小不必精细化
 *   <li>无 Stale PUBLISHING 重置:退避 + 上限 attempts 兜底,不需要单独清扫(留作 follow-up 优化)
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    prefix = "batch.trigger.async-launch",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TriggerOutboxRelay {

  private static final Duration LOCK_AT_MOST = Duration.ofMinutes(1);
  private static final Duration LOCK_AT_LEAST = Duration.ofSeconds(2);

  /** 退避上限,单条失败后最长 60s 后重试(2^6 = 64 → 60s 截断)。 */
  private static final long MAX_BACKOFF_SECONDS = 60L;

  private final TriggerOutboxEventMapper mapper;
  private final TriggerEventPublisher publisher;
  private final LockingTaskExecutor lockingTaskExecutor;

  @Value("${batch.trigger.outbox.poll-interval-millis:200}")
  private long pollIntervalMillis;

  @Value("${batch.trigger.outbox.batch-size:100}")
  private int batchSize;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private ScheduledExecutorService executor;

  @PostConstruct
  public void start() {
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
      return;
    }
    String messageKey = event.getTenantId() + ":" + event.getRequestId();
    TriggerEventPublisher.PublishResult result =
        publisher.publish(event.getTopic(), messageKey, envelope, event.getTraceId());
    if (result.success()) {
      mapper.markPublished(event.getId(), OutboxPublishStatus.PUBLISHED.code());
    } else {
      Instant retryAt = Instant.now().plusSeconds(backoffSeconds(event.getPublishAttempt() + 1));
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

  private LockConfiguration lockConfig() {
    return new LockConfiguration(
        Instant.now(), "trigger_outbox_relay", LOCK_AT_MOST, LOCK_AT_LEAST);
  }
}
