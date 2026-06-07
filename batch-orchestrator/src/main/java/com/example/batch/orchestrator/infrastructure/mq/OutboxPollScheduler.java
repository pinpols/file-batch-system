package com.example.batch.orchestrator.infrastructure.mq;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.application.engine.DefaultScheduleForwarder;
import com.example.batch.orchestrator.application.engine.ScheduleForwarderResult;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.config.OrchestratorAsyncConfiguration;
import com.example.batch.orchestrator.config.OutboxProperties;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.infrastructure.sharding.ShardAssignment;
import com.example.batch.orchestrator.infrastructure.sharding.ShardAssignmentProvider;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Outbox 轮询调度器（自适应版本）：驱动 {@link DefaultScheduleForwarder} 把已落库的 outbox_event 推到 Kafka。
 *
 * <p>自适应轮询间隔：
 *
 * <ul>
 *   <li>有事件被处理（积压）：立即以 {@code minPollIntervalMillis} 调度下一轮，保证低延迟。
 *   <li>无事件（空闲）：间隔乘以 {@code backoffMultiplier}，退避至 {@code pollIntervalMillis} 上限，减少空转 DB 查询。
 * </ul>
 *
 * <p>关键机制：
 *
 * <ul>
 *   <li><b>分布式互斥</b>：通过 ShedLock 持锁执行；{@code shardTotal > 1} 时每个分片独立锁 （{@code
 *       outbox_poll_shard_N}），允许多实例并行处理不同分片；默认 shardTotal=1 时退化为单锁 {@code outbox_poll} 与原行为兼容。
 *   <li><b>Stale PUBLISHING 重置</b>：每轮开头把超过 {@code publishingTimeoutSeconds} 仍停留在 PUBLISHING 的事件 拨回
 *       FAILED——防止 Kafka send 卡死/JVM 崩溃导致事件永久卡在 PUBLISHING 无人接管。
 *   <li><b>熔断联动</b>：{@link OutboxPublishCircuitBreaker} 打开时整轮跳过；advance 完成后把本轮失败数喂回熔断器。
 *   <li><b>优雅下线</b>：{@code gracefulShutdown.isDraining()} 为 true 时跳过本轮，让现有任务收尾后进程退出。
 * </ul>
 *
 * <p>替代原 {@code @Scheduled(fixedDelay)} 方案，通过 {@link ScheduledExecutorService} 自调度实现动态间隔。
 *
 * <p>首轮调度延迟到 {@link ApplicationReadyEvent}（Flyway 等初始化完成之后），避免迁移未完成即访问 {@code batch.outbox_event}
 * 引发瞬时 DDL race。
 */
@Component
@Slf4j
@Lazy(false)
public class OutboxPollScheduler {

  /**
   * ShedLock 兜底持锁上限的余量。在 {@link OutboxProperties#getPublishingTimeoutSeconds()} 基础上加这一段缓冲得到 锁的
   * lockAtMost — 保证锁过期晚于 stale 重置阈值,避免"锁过期但 stale 未到"的盲窗;同时把窗口控制在 10s, 不至于让单实例长时间故障后才能切换。
   *
   * <p>历史问题:lockAtMost 与 publishingTimeoutSeconds 严格相等时,GC 停顿或 Kafka 超时期间锁刚过期就被另一
   * 实例抢走,原实例继续推进与新实例并发处理同一批 outbox 事件(outbox UNIQUE(tenant_id, event_key) ON CONFLICT
   * 兜底但会刷错误日志)。+10s 缓冲将这种竞争窗口压缩到极小。
   */
  private static final Duration LOCK_AT_MOST_BUFFER = Duration.ofSeconds(10);

  /**
   * ShedLock 最小持锁时长 — 200ms(从原 3s 降下来,2026-05-01 校准)。
   *
   * <p>目的:让闲置 instance 不长占锁;3s 太长造成多 instance 部署时另一个 instance 一旦抢到锁就要至少憋 3s 才放,即使本轮没事可做。200ms
   * 仍能覆盖单轮 advance 平均耗时(~50ms)防止瞬抢/瞬释抖动。
   */
  private static final Duration LOCK_AT_LEAST = Duration.ofMillis(200);

  private final DefaultScheduleForwarder scheduleForwarder;
  private final OutboxPublishCircuitBreaker outboxPublishCircuitBreaker;
  private final BatchOrchestratorGovernanceProperties governance;
  private final LockingTaskExecutor lockingTaskExecutor;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final OutboxEventMapper outboxEventMapper;
  private final ShardAssignmentProvider shardAssignmentProvider;
  private final ThreadPoolTaskScheduler executor;

  private final AtomicBoolean pollingLoopStarted = new AtomicBoolean(false);
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong currentIntervalMillis = new AtomicLong(0);

  @SuppressWarnings("PMD.ExcessiveParameterList") // 8 项依赖均单一职责,封装 Command 类反而模糊语义
  public OutboxPollScheduler(
      DefaultScheduleForwarder scheduleForwarder,
      OutboxPublishCircuitBreaker outboxPublishCircuitBreaker,
      BatchOrchestratorGovernanceProperties governance,
      LockingTaskExecutor lockingTaskExecutor,
      OrchestratorGracefulShutdown gracefulShutdown,
      OutboxEventMapper outboxEventMapper,
      ShardAssignmentProvider shardAssignmentProvider,
      @Qualifier(OrchestratorAsyncConfiguration.OUTBOX_POLL_SCHEDULER)
          ThreadPoolTaskScheduler executor) {
    this.scheduleForwarder = scheduleForwarder;
    this.outboxPublishCircuitBreaker = outboxPublishCircuitBreaker;
    this.governance = governance;
    this.lockingTaskExecutor = lockingTaskExecutor;
    this.gracefulShutdown = gracefulShutdown;
    this.outboxEventMapper = outboxEventMapper;
    this.shardAssignmentProvider = shardAssignmentProvider;
    this.executor = executor;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady(ApplicationReadyEvent ignored) {
    OutboxProperties outbox = governance.outbox();
    // STATIC 模式下校验 ENV 里的 shard 配置合法性；DYNAMIC 模式下由 ShardAssignmentProvider 保证
    if (outbox.getShardingMode() == OutboxProperties.ShardingMode.STATIC
        && (outbox.getShardIndex() < 0 || outbox.getShardIndex() >= outbox.getShardTotal())) {
      throw new IllegalStateException(
          "Outbox 分片配置非法：shardIndex="
              + outbox.getShardIndex()
              + " 必须在 [0, shardTotal="
              + outbox.getShardTotal()
              + ") 范围内");
    }
    if (!pollingLoopStarted.compareAndSet(false, true)) {
      return;
    }
    long initialDelay = outbox.getMinPollIntervalMillis();
    currentIntervalMillis.set(initialDelay);
    executor.schedule(
        this::pollAndReschedule, BatchDateTimeSupport.utcNow().plusMillis(initialDelay));
    ShardAssignment initial = shardAssignmentProvider.current();
    log.info(
        "OutboxPollScheduler 已启动（自适应模式）：min={}ms max={}ms backoff={}x mode={} shard={}/{}",
        outbox.getMinPollIntervalMillis(),
        outbox.getPollIntervalMillis(),
        outbox.getBackoffMultiplier(),
        outbox.getShardingMode(),
        initial.shardIndex(),
        initial.shardTotal());
  }

  // P1 治理: executor 生命周期改由 Spring 容器统一管理 (outboxPollScheduler bean), 这里不再
  // 手动 shutdown / awaitTermination — Spring 通过 awaitTerminationSeconds=30 等价兜底。
  // 原 setDaemon(true) 副作用 (JVM 退出时 daemon 线程被直接终止, awaitTermination 形同虚设)
  // 也因 bean 切换为非 daemon 一并修复。

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
    if (gracefulShutdown.isDraining()) {
      // 前置短路：shutdown 已触发时不再去抢 ShedLock（Lettuce 可能已 STOPPED，
      // 抢锁会抛 IllegalStateException），内层 executeAdvance 的 isDraining 判断
      // 仅在拿到锁后生效，这里补一层保证 shutdown 期间不产生 ERROR 日志。
      running.set(false);
      scheduleNext(null);
      return;
    }
    ScheduleForwarderResult[] holder = new ScheduleForwarderResult[1];
    try {
      lockingTaskExecutor.executeWithLock(
          (LockingTaskExecutor.Task) () -> holder[0] = executeAdvance(), lockConfig());
    } catch (DataAccessException dae) {
      // 数据库连接/查询异常 — 瞬时故障（PG 重启 / 网络抖动），自动退避重试，不需要人介入
      // 用 WARN 而非 ERROR：ERROR 留给真正不可恢复 / 需要人介入的场景
      log.warn(
          "Outbox 轮询数据库瞬时异常，下轮重试: {}",
          dae.getMostSpecificCause() == null
              ? dae.getMessage()
              : dae.getMostSpecificCause().getMessage());
      log.debug("Outbox 轮询数据库异常详细堆栈", dae);
    } catch (OutOfMemoryError oom) {
      // 内存溢出 — 严重故障，记录后让 JVM 默认 OOM handler 接管
      log.error("Outbox 轮询遭遇 OOM，进程可能需要重启", oom);
      throw oom;
    } catch (Throwable t) {
      // 其他异常（Kafka 故障、序列化错误等）—— 真异常用 ERROR
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
    ShardAssignment assignment = shardAssignmentProvider.current();
    SchedulePlan plan = new SchedulePlan();
    plan.setShardTotal(assignment.shardTotal());
    plan.setShardIndex(assignment.shardIndex());
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
      // 瞬时 PG 不可用，下轮自然重试；ERROR 留给真异常
      log.warn(
          "重置滞留 PUBLISHING 事件失败（数据库瞬时异常，下轮重试）: {}",
          ex.getMostSpecificCause() == null
              ? ex.getMessage()
              : ex.getMostSpecificCause().getMessage());
      log.debug("重置滞留 PUBLISHING 事件异常详细堆栈", ex);
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

    if (executor.getScheduledExecutor().isShutdown()) {
      return;
    }
    try {
      executor.schedule(
          this::pollAndReschedule, BatchDateTimeSupport.utcNow().plusMillis(nextDelay));
    } catch (RejectedExecutionException ex) {
      if (gracefulShutdown.isDraining() || executor.getScheduledExecutor().isShutdown()) {
        log.debug("Outbox 下次轮询跳过：调度器正在关闭");
        return;
      }
      throw ex;
    }
  }

  /**
   * shardTotal = 1：lock name 为 "outbox_poll"，与原行为完全兼容。 shardTotal > 1：每个分片独立持锁，允许多实例并行。
   *
   * <p>DYNAMIC 模式下每轮都重新查询分配；rebalance 期间可能有短暂重叠，由 Outbox 事件幂等设计兜底。
   */
  private LockConfiguration lockConfig() {
    ShardAssignment assignment = shardAssignmentProvider.current();
    String lockName =
        assignment.shardTotal() > 1
            ? "outbox_poll_shard_" + assignment.shardIndex()
            : "outbox_poll";
    Instant now = BatchDateTimeSupport.utcNow();
    Duration lockAtMost =
        Duration.ofSeconds(governance.outbox().getPublishingTimeoutSeconds())
            .plus(LOCK_AT_MOST_BUFFER);
    return new LockConfiguration(now, lockName, lockAtMost, LOCK_AT_LEAST);
  }
}
