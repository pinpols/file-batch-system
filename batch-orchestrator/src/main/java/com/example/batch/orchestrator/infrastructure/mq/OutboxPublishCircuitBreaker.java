package com.example.batch.orchestrator.infrastructure.mq;

import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.config.OutboxProperties;
import com.example.batch.common.redis.BatchRedisKeys;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Outbox 投递的全局熔断器：当持续出现投递失败时，暂时停止推进 outbox，
 * 防止失败重试造成的雪崩式数据库/消息堆积。
 */
@Component
public class OutboxPublishCircuitBreaker {

    private static final String FIELD_FAILED_POLLS = "failedPolls";
    private static final String FIELD_OPEN_UNTIL_MS = "openUntilMs";
    private static final String ALLOW_SCRIPT = """
            local openUntil = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
            if openUntil <= tonumber(ARGV[2]) then
              return 1
            end
            return 0
            """;
    private static final String ADVANCE_SCRIPT = """
            local failedField = ARGV[1]
            local openField = ARGV[2]
            local failed = tonumber(ARGV[3])
            local threshold = tonumber(ARGV[4])
            local cooldownMillis = tonumber(ARGV[5])
            local now = tonumber(ARGV[6])
            local ttlMillis = tonumber(ARGV[7])
            if failed > 0 then
              local failedPolls = tonumber(redis.call('HGET', KEYS[1], failedField) or '0') + 1
              local openUntil = tonumber(redis.call('HGET', KEYS[1], openField) or '0')
              if failedPolls >= threshold then
                openUntil = now + cooldownMillis
                failedPolls = 0
              end
              redis.call('HSET', KEYS[1], failedField, failedPolls, openField, openUntil)
              redis.call('PEXPIRE', KEYS[1], ttlMillis)
              return openUntil
            end
            redis.call('HSET', KEYS[1], failedField, 0, openField, 0)
            redis.call('PEXPIRE', KEYS[1], ttlMillis)
            return 0
            """;

    private final OutboxProperties outboxProperties;
    private final OrchestratorRedisSupport redis;

    public OutboxPublishCircuitBreaker(BatchOrchestratorGovernanceProperties governance,
                                       OrchestratorRedisSupport redis) {
        this.outboxProperties = governance.outbox();
        this.redis = redis;
    }

    /**
     * 当前轮是否允许推进投递。
     */
    public boolean allowNow() {
        if (!outboxProperties.isCircuitBreakerEnabled()) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long allowed = redis.evalLong(
                ALLOW_SCRIPT,
                BatchRedisKeys.outboxCircuit(),
                FIELD_OPEN_UNTIL_MS,
                String.valueOf(now)
        );
        return allowed == null || allowed > 0;
    }

    /**
     * 根据本轮推进结果更新熔断状态。
     */
    public void onAdvanceResult(int publishFailed) {
        if (!outboxProperties.isCircuitBreakerEnabled()) {
            return;
        }
        long ttlMillis = Math.max(outboxProperties.getCircuitBreakerCooldownMillis(), 60_000L);
        redis.evalLong(
                ADVANCE_SCRIPT,
                BatchRedisKeys.outboxCircuit(),
                FIELD_FAILED_POLLS,
                FIELD_OPEN_UNTIL_MS,
                String.valueOf(Math.max(publishFailed, 0)),
                String.valueOf(outboxProperties.getCircuitBreakerFailureThresholdConsecutivePolls()),
                String.valueOf(outboxProperties.getCircuitBreakerCooldownMillis()),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(ttlMillis)
        );
    }
}
