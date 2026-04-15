package com.example.batch.orchestrator.infrastructure.mq;

import com.example.batch.common.redis.BatchRedisKeys;
import com.example.batch.orchestrator.config.OutboxProperties;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import org.springframework.stereotype.Component;

/** Outbox 投递的全局熔断器：当持续出现投递失败时，暂时停止推进 outbox， 防止失败重试造成的雪崩式数据库/消息堆积。 */
@Component
public class OutboxPublishCircuitBreaker {

  private static final String FIELD_FAILED_POLLS = "failedPolls";
  private static final String FIELD_OPEN_UNTIL_MS = "openUntilMs";
  // 返回 openUntilMs 原始值：<= now 表示熔断关闭，> now 表示熔断开启中
  private static final String ALLOW_SCRIPT =
      """
      return tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
      """;
  private static final String ADVANCE_SCRIPT =
      """
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

  // 本地缓存的熔断开启截止时间（毫秒时间戳）；0 表示熔断关闭
  private volatile long cachedOpenUntilMs = 0;
  // 关闭状态缓存的到期时间；到期后需重新查询 Redis 确认状态
  private volatile long closedCacheExpiresAt = 0;
  // #4-2: 半开探测标记——冷却期过后只允许一次探测请求，成功才完全关闭
  private volatile boolean halfOpenProbing = false;

  public OutboxPublishCircuitBreaker(
      BatchOrchestratorGovernanceProperties governance, OrchestratorRedisSupport redis) {
    this.outboxProperties = governance.outbox();
    this.redis = redis;
  }

  /**
   * 当前轮是否允许推进投递。
   *
   * <p>快速路径：本地缓存命中时不访问 Redis。 慢速路径：缓存未命中时查询 Redis 并刷新本地缓存。
   */
  public boolean allowNow() {
    if (!outboxProperties.isCircuitBreakerEnabled()) {
      return true;
    }
    long now = System.currentTimeMillis();
    // 快速路径 1：本地已知熔断开启，且冷却期未结束
    if (cachedOpenUntilMs > now) {
      return false;
    }
    // #4-2: 冷却期结束时进入半开状态，仅放行一次探测请求
    if (cachedOpenUntilMs > 0 && cachedOpenUntilMs <= now && !halfOpenProbing) {
      halfOpenProbing = true;
      return true;
    }
    // 快速路径 2：本地已知熔断关闭，且关闭缓存仍有效
    if (cachedOpenUntilMs == 0 && now < closedCacheExpiresAt) {
      return true;
    }
    // 慢速路径：查询 Redis，刷新本地缓存
    Long openUntilMs =
        redis.evalLong(ALLOW_SCRIPT, BatchRedisKeys.outboxCircuit(), FIELD_OPEN_UNTIL_MS);
    cachedOpenUntilMs = openUntilMs != null ? openUntilMs : 0;
    closedCacheExpiresAt = now + outboxProperties.getPollIntervalMillis();
    return cachedOpenUntilMs <= now;
  }

  /** 根据本轮推进结果更新熔断状态，并同步刷新本地缓存。 */
  public void onAdvanceResult(int publishFailed) {
    if (!outboxProperties.isCircuitBreakerEnabled()) {
      return;
    }
    long now = System.currentTimeMillis();
    long ttlMillis = Math.max(outboxProperties.getCircuitBreakerCooldownMillis(), 60_000L);
    Long openUntilMs =
        redis.evalLong(
            ADVANCE_SCRIPT,
            BatchRedisKeys.outboxCircuit(),
            FIELD_FAILED_POLLS,
            FIELD_OPEN_UNTIL_MS,
            String.valueOf(Math.max(publishFailed, 0)),
            String.valueOf(outboxProperties.getCircuitBreakerFailureThresholdConsecutivePolls()),
            String.valueOf(outboxProperties.getCircuitBreakerCooldownMillis()),
            String.valueOf(now),
            String.valueOf(ttlMillis));
    // 用 Redis 返回的最新值刷新本地缓存
    cachedOpenUntilMs = openUntilMs != null ? openUntilMs : 0;
    closedCacheExpiresAt = now + outboxProperties.getPollIntervalMillis();
    // #4-2: 探测完成后重置半开标记
    if (halfOpenProbing) {
      halfOpenProbing = false;
      if (publishFailed > 0) {
        // 探测失败：熔断器仍处于打开状态（Redis 脚本已更新 openUntilMs）
      }
      // 探测成功：cachedOpenUntilMs 已为 0，自然进入关闭状态
    }
  }
}
