package com.example.batch.orchestrator.infrastructure.mq;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.orchestrator.application.engine.DefaultScheduleForwarder;
import com.example.batch.orchestrator.application.engine.ScheduleForwarderResult;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.config.OutboxProperties;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Outbox 轮询调度器（自适应版本）。
 *
 * <p>轮询间隔根据上一轮结果动态调整：
 *
 * <ul>
 *   <li>有事件被处理（积压）：立即以 {@code minPollIntervalMillis} 调度下一轮，保证低延迟。
 *   <li>无事件（空闲）：间隔乘以 {@code backoffMultiplier}，退避至 {@code pollIntervalMillis} 上限，减少空转 DB 查询。
 * </ul>
 *
 * <p>替代原 {@code @Scheduled(fixedDelay)} 方案，通过 {@link ScheduledExecutorService} 自调度实现动态间隔；分布式互斥仍由
 * ShedLock 保证。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPollScheduler {

  private static final Duration LOCK_AT_MOST = Duration.ofMinutes(1);
  private static final Duration LOCK_AT_LEAST = Duration.ofSeconds(3);

  private final DefaultScheduleForwarder scheduleForwarder;
  private final OutboxPublishCircuitBreaker outboxPublishCircuitBreaker;
  private final BatchOrchestratorGovernanceProperties governance;
  private final LockingTaskExecutor lockingTaskExecutor;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final OutboxEventMapper outboxEventMapper;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong currentIntervalMillis = new AtomicLong(0);

  private ScheduledExecutorService executor;

  @PostConstruct
  public void start() {
    // #10-2: 启动时校验分片配置合法性
    OutboxProperties outbox = governance.outbox();
    if (outbox.getShardIndex() < 0 || outbox.getShardIndex() >= outbox.getShardTotal()) {
      throw new IllegalStateException(
          "Outbox 分片配置非法：shardIndex="
              + outbox.getShardIndex()
              + " 必须在 [0, shardTotal="
              + outbox.getShardTotal()
              + ") 范围内");
    }
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "outbox-poll-scheduler");
              t.setDaemon(true);
              return t;
            });
    long initialDelay = outbox.getMinPollIntervalMillis();
    currentIntervalMillis.set(initialDelay);
    executor.schedule(this::pollAndReschedule, initialDelay, TimeUnit.MILLISECONDS);
    log.info(
        "OutboxPollScheduler 已启动（自适应模式）：min={}ms max={}ms backoff={}x shard={}/{}",
        outbox.getMinPollIntervalMillis(),
        outbox.getPollIntervalMillis(),
        outbox.getBackoffMultiplier(),
        outbox.getShardIndex(),
        outbox.getShardTotal());
  }

  @PreDestroy
  public void stop() {
    if (executor == null) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        log.warn("OutboxPollScheduler 未在 30s 内完成关闭，强制中断");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      log.warn("OutboxPollScheduler awaitTermination 被中断，强制中断");
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /** 供单元测试直接触发一次轮询（不走自调度循环）。 */
  public void poll() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      lockingTaskExecutor.executeWithLock((LockingTaskExecutor.Task) this::doPoll, lockConfig());
    } catch (Throwable t) {
      log.error("Outbox 轮询异常", t);
    } finally {
      running.set(false);
    }
  }

  private void pollAndReschedule() {
    if (!running.compareAndSet(false, true)) {
      // 上一轮仍在执行，视为繁忙但不累积任务，退避后重试
      scheduleNext(null);
      return;
    }
    @SuppressWarnings("unchecked")
    ScheduleForwarderResult[] holder = new ScheduleForwarderResult[1];
    try {
      lockingTaskExecutor.executeWithLock(
          (LockingTaskExecutor.Task) () -> holder[0] = executeAdvance(), lockConfig());
    } catch (DataAccessException dae) {
      // #7-1: 数据库连接/查询异常 — 可能是瞬时故障，下轮退避重试
      log.error("Outbox 轮询数据库异常（瞬时故障，等待退避重试）", dae);
    } catch (OutOfMemoryError oom) {
      // #7-1: 内存溢出 — 严重故障，记录后让 JVM 默认 OOM handler 接管
      log.error("Outbox 轮询遭遇 OOM，进程可能需要重启", oom);
      throw oom;
    } catch (Throwable t) {
      // #7-1: 其他异常（Kafka 故障、序列化错误等）
      log.error("Outbox 轮询异常（非数据库类）", t);
    } finally {
      running.set(false);
      scheduleNext(holder[0]);
    }
  }

  private ScheduleForwarderResult executeAdvance() {
    if (gracefulShutdown.isDraining()) {
      log.info("Outbox 轮询跳过：orchestrator 正在 draining");
      return null;
    }
    if (!outboxPublishCircuitBreaker.allowNow()) {
      log.warn("Outbox 投递熔断已打开：跳过推进（cooldown 中）");
      return null;
    }
    OutboxProperties outbox = governance.outbox();
    // #1-1: 每轮开始前将超时的 PUBLISHING 事件重置为 FAILED，防止 Kafka 投递失败后事件永久卡死
    resetStalePublishingEvents(outbox);
    SchedulePlan plan = new SchedulePlan();
    plan.setShardTotal(outbox.getShardTotal());
    plan.setShardIndex(outbox.getShardIndex());
    ScheduleForwarderResult result = scheduleForwarder.advance(plan);
    outboxPublishCircuitBreaker.onAdvanceResult(result == null ? 0 : result.totalFailures());
    return result;
  }

  private void resetStalePublishingEvents(OutboxProperties outbox) {
    try {
      int reset =
          outboxEventMapper.resetStalePublishing(
              OutboxPublishStatus.PUBLISHING.code(),
              OutboxPublishStatus.FAILED.code(),
              outbox.getPublishingTimeoutSeconds());
      if (reset > 0) {
        log.warn("重置 {} 条滞留 PUBLISHING 状态的 outbox 事件为 FAILED", reset);
      }
    } catch (DataAccessException ex) {
      log.error("重置滞留 PUBLISHING 事件失败（数据库异常，下轮重试）", ex);
    }
  }

  private void doPoll() {
    executeAdvance();
  }

  /**
   * 根据本轮结果计算下一轮调度延迟：
   *
   * <ul>
   *   <li>本轮有事件（busy）→ 立即以 minPollIntervalMillis 重调。
   *   <li>本轮空闲 / null → 当前间隔 × backoffMultiplier，上限 pollIntervalMillis（最大间隔）。
   * </ul>
   */
  private void scheduleNext(ScheduleForwarderResult result) {
    OutboxProperties outbox = governance.outbox();
    long min = outbox.getMinPollIntervalMillis();
    long max = outbox.getPollIntervalMillis();

    long nextDelay;
    if (result != null && result.attemptedEvents() > 0) {
      nextDelay = min;
    } else {
      long current = currentIntervalMillis.get();
      nextDelay = (long) (current * outbox.getBackoffMultiplier());
      nextDelay = Math.min(nextDelay, max);
      nextDelay = Math.max(nextDelay, min);
    }
    currentIntervalMillis.set(nextDelay);
    log.debug(
        "Outbox 下次轮询延迟 {}ms（attempted={}）",
        nextDelay,
        result == null ? "n/a" : result.attemptedEvents());

    if (executor != null && !executor.isShutdown()) {
      executor.schedule(this::pollAndReschedule, nextDelay, TimeUnit.MILLISECONDS);
    }
  }

  /** shardTotal = 1（默认）：lock name 为 "outbox_poll"，与原行为完全兼容。 shardTotal > 1：每个分片独立持锁，允许多实例并行。 */
  private LockConfiguration lockConfig() {
    int shardTotal = governance.outbox().getShardTotal();
    String lockName =
        shardTotal > 1 ? "outbox_poll_shard_" + governance.outbox().getShardIndex() : "outbox_poll";
    Instant now = Instant.now();
    return new LockConfiguration(now, lockName, LOCK_AT_MOST, LOCK_AT_LEAST);
  }
}
