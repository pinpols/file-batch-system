package com.example.batch.orchestrator.infrastructure.mq;

import com.example.batch.orchestrator.application.engine.DefaultScheduleForwarder;
import com.example.batch.orchestrator.application.engine.ScheduleForwarderResult;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.config.OutboxProperties;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
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
import org.springframework.stereotype.Component;

/**
 * Outbox 轮询调度器（自适应版本）。
 *
 * <p>轮询间隔根据上一轮结果动态调整：
 * <ul>
 *   <li>有事件被处理（积压）：立即以 {@code minPollIntervalMillis} 调度下一轮，保证低延迟。</li>
 *   <li>无事件（空闲）：间隔乘以 {@code backoffMultiplier}，退避至 {@code pollIntervalMillis}
 *       上限，减少空转 DB 查询。</li>
 * </ul>
 *
 * <p>替代原 {@code @Scheduled(fixedDelay)} 方案，通过 {@link ScheduledExecutorService}
 * 自调度实现动态间隔；分布式互斥仍由 ShedLock 保证。
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

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong currentIntervalMillis = new AtomicLong(0);

    private ScheduledExecutorService executor;

    @PostConstruct
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-poll-scheduler");
            t.setDaemon(true);
            return t;
        });
        OutboxProperties outbox = governance.outbox();
        long initialDelay = outbox.getMinPollIntervalMillis();
        currentIntervalMillis.set(initialDelay);
        executor.schedule(this::pollAndReschedule, initialDelay, TimeUnit.MILLISECONDS);
        log.info("OutboxPollScheduler 已启动（自适应模式）：min={}ms max={}ms backoff={}x",
                outbox.getMinPollIntervalMillis(),
                outbox.getPollIntervalMillis(),
                outbox.getBackoffMultiplier());
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    /**
     * 供单元测试直接触发一次轮询（不走自调度循环）。
     */
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

    // ── 内部 ──────────────────────────────────────────────────────────────────

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
                    (LockingTaskExecutor.Task) () -> holder[0] = executeAdvance(),
                    lockConfig());
        } catch (Throwable t) {
            log.error("Outbox 轮询异常", t);
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
        SchedulePlan plan = new SchedulePlan();
        plan.setShardTotal(outbox.getShardTotal());
        plan.setShardIndex(outbox.getShardIndex());
        ScheduleForwarderResult result = scheduleForwarder.advance(plan);
        outboxPublishCircuitBreaker.onAdvanceResult(result == null ? 0 : result.totalFailures());
        return result;
    }

    private void doPoll() {
        executeAdvance();
    }

    /**
     * 根据本轮结果计算下一轮调度延迟：
     * <ul>
     *   <li>本轮有事件（busy）→ 立即以 minPollIntervalMillis 重调。</li>
     *   <li>本轮空闲 / null → 当前间隔 × backoffMultiplier，上限 pollIntervalMillis（最大间隔）。</li>
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
        log.debug("Outbox 下次轮询延迟 {}ms（attempted={}）",
                nextDelay, result == null ? "n/a" : result.attemptedEvents());

        if (executor != null && !executor.isShutdown()) {
            executor.schedule(this::pollAndReschedule, nextDelay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * shardTotal = 1（默认）：lock name 为 "outbox_poll"，与原行为完全兼容。
     * shardTotal > 1：每个分片独立持锁，允许多实例并行。
     */
    private LockConfiguration lockConfig() {
        int shardTotal = governance.outbox().getShardTotal();
        String lockName = shardTotal > 1
                ? "outbox_poll_shard_" + governance.outbox().getShardIndex()
                : "outbox_poll";
        Instant now = Instant.now();
        return new LockConfiguration(now, lockName, LOCK_AT_MOST, LOCK_AT_LEAST);
    }
}
