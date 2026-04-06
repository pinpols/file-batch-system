package com.example.batch.orchestrator.infrastructure.mq;

import com.example.batch.orchestrator.application.engine.DefaultScheduleForwarder;
import com.example.batch.orchestrator.application.engine.ScheduleForwarderResult;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${batch.outbox.poll-interval-millis:5000}")
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

    private void doPoll() {
        if (!outboxPublishCircuitBreaker.allowNow()) {
            log.warn("Outbox 投递熔断已打开：跳过推进（cooldown 中）");
            return;
        }
        int shardTotal = governance.outbox().getShardTotal();
        int shardIndex = governance.outbox().getShardIndex();
        SchedulePlan plan = new SchedulePlan();
        plan.setShardTotal(shardTotal);
        plan.setShardIndex(shardIndex);
        ScheduleForwarderResult result = scheduleForwarder.advance(plan);
        outboxPublishCircuitBreaker.onAdvanceResult(result == null ? 0 : result.totalFailures());
    }

    /**
     * shardTotal = 1（默认）：lock name 为 "outbox_poll"，与原行为完全兼容。
     * shardTotal > 1：每个分片独立持锁，lock name 为 "outbox_poll_shard_{N}"，允许多实例并行。
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
