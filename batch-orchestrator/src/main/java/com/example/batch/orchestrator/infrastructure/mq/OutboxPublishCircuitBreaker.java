package com.example.batch.orchestrator.infrastructure.mq;

import com.example.batch.orchestrator.config.OutboxProperties;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Outbox 投递的全局熔断器：当持续出现投递失败时，暂时停止推进 outbox，
 * 防止失败重试造成的雪崩式数据库/消息堆积。
 */
@Component
public class OutboxPublishCircuitBreaker {

    private final OutboxProperties outboxProperties;
    private final AtomicInteger consecutiveFailedPolls = new AtomicInteger(0);

    /**
     * 熔断打开后，直到该时间点前都不允许推进。
     * 使用 epochMilli，避免在时钟回拨时产生复杂问题。
     */
    private volatile long openUntilEpochMilli = 0L;

    public OutboxPublishCircuitBreaker(OutboxProperties outboxProperties) {
        this.outboxProperties = outboxProperties;
    }

    /**
     * 当前轮是否允许推进投递。
     */
    public boolean allowNow() {
        if (!outboxProperties.isCircuitBreakerEnabled()) {
            return true;
        }
        long now = System.currentTimeMillis();
        return now >= openUntilEpochMilli;
    }

    /**
     * 根据本轮推进结果更新熔断状态。
     */
    public void onAdvanceResult(int publishFailed) {
        if (!outboxProperties.isCircuitBreakerEnabled()) {
            return;
        }

        if (publishFailed > 0) {
            int failedPolls = consecutiveFailedPolls.incrementAndGet();
            if (failedPolls >= outboxProperties.getCircuitBreakerFailureThresholdConsecutivePolls()) {
                long now = System.currentTimeMillis();
                openUntilEpochMilli = now + outboxProperties.getCircuitBreakerCooldownMillis();
                consecutiveFailedPolls.set(0);
            }
            return;
        }

        consecutiveFailedPolls.set(0);
        openUntilEpochMilli = 0L;
    }
}

