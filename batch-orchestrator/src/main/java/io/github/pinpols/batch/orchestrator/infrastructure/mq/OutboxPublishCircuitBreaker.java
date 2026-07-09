package io.github.pinpols.batch.orchestrator.infrastructure.mq;

import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.config.OutboxProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Outbox 投递的集群级熔断器：连续投递失败累积到阈值后打开熔断，强制 {@link OutboxPollScheduler} 暂停推进， 避免失败重试造成的雪崩式 DB 写入 / Kafka
 * 重投 / 告警轰失败。
 *
 * <p>状态通过 Redis Hash 共享（而非 in-process），多实例部署下所有 orchestrator 看到同一个熔断状态。
 *
 * <p>关键机制：
 *
 * <ul>
 *   <li><b>快/慢双路径</b>：本地缓存 {@code cachedOpenUntilMs} 命中时不访问 Redis，只有缓存过期或首次查询才走 Redis Lua 脚本。
 *   <li><b>半开探测（CAS 独占）</b>：冷却期结束时进入半开态，{@code halfOpenProbing.compareAndSet(false, true)}
 *       保证只有一个线程执行探测轮——成功则脚本会把 openUntilMs 清 0，失败则熔断再次打开（见 {@code C-5.1}）。
 *   <li><b>阈值计数</b>：累计失败轮达到 {@code circuitBreakerFailureThresholdConsecutivePolls} 后打开， 冷却期 {@code
 *       circuitBreakerCooldownMillis}；任一成功轮清零计数。
 * </ul>
 */
@Slf4j
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
  private final MeterRegistry meterRegistry;

  /**
   * A5(outbox 部分):Redis 抖动时 allowNow/onAdvanceResult 走 fail-open 回落缓存态的累计次数。 非零速率=集群熔断态与 Redis
   * 失联,虽不影响投递(事件已同事务落 PG)但说明基础设施降级,需运维关注。
   */
  private Counter failOpenCounter;

  /**
   * T-2：把 {@code cachedOpenUntilMs} + {@code closedCacheExpiresAt} 捆绑成单个不可变 record， 通过 volatile
   * 引用原子发布/读取，消除两字段的 torn-read（旧实现两个 volatile long 分别读取，高并发下可能组合出不存在的中间状态）。
   */
  private record CircuitState(long openUntilMs, long closedCacheExpiresAt) {
    static final CircuitState CLOSED_EMPTY = new CircuitState(0L, 0L);
  }

  private volatile CircuitState state = CircuitState.CLOSED_EMPTY;
  // C-5.1: 用 AtomicBoolean.compareAndSet 保证只有一个线程进入半开探测
  private final AtomicBoolean halfOpenProbing = new AtomicBoolean(false);

  public OutboxPublishCircuitBreaker(
      BatchOrchestratorGovernanceProperties governance,
      OrchestratorRedisSupport redis,
      MeterRegistry meterRegistry) {
    this.outboxProperties = governance.outbox();
    this.redis = redis;
    this.meterRegistry = meterRegistry;
  }

  /**
   * O1:cluster-wide outbox 熔断可观测。DispatchChannel 有 {@code batch.dispatch.circuits.open} gauge,
   * 这个集群级的此前唯一痕迹是每轮 log.warn。
   *
   * <ul>
   *   <li>{@code batch.outbox.circuit.open}(0/1 gauge):熔断打开=整个集群 outbox 投递暂停,最严重降级之一;
   *   <li>{@code batch.outbox.circuit.failopen.total}(counter):Redis 抖动放行(fail-open)的累计次数。
   * </ul>
   */
  @PostConstruct
  void initMetrics() {
    Gauge.builder("batch.outbox.circuit.open", this, OutboxPublishCircuitBreaker::openStateSample)
        .description(
            "Cluster-wide outbox publish circuit breaker state (1=open → all orchestrators pause"
                + " outbox delivery; 0=closed). One of the most severe degradations.")
        .register(meterRegistry);
    failOpenCounter =
        Counter.builder("batch.outbox.circuit.failopen.total")
            .description(
                "Outbox circuit breaker fell back to cached state because Redis was unreachable"
                    + " (fail-open). Non-zero rate signals Redis degradation, not delivery loss.")
            .register(meterRegistry);
  }

  /**
   * gauge 采样:读本地已发布的一致快照(不访问 Redis)。{@code openUntilMs > now} 即视为开态。 采样精度受快慢双路径缓存影响(与 allowNow
   * 同源),用于告警足够。
   */
  private int openStateSample() {
    return state.openUntilMs() > BatchDateTimeSupport.utcEpochMillis() ? 1 : 0;
  }

  private void recordFailOpen() {
    if (failOpenCounter != null) {
      failOpenCounter.increment();
    }
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
    long now = BatchDateTimeSupport.utcEpochMillis();
    // T-2：单次 volatile 读取拿到一致的 (openUntilMs, closedCacheExpiresAt) 快照
    CircuitState snapshot = state;
    // 快速路径 1：本地已知熔断开启，且冷却期未结束
    if (snapshot.openUntilMs() > now) {
      return false;
    }
    // C-5.1: 冷却期结束时进入半开状态，CAS 保证仅一个线程执行探测
    if (snapshot.openUntilMs() > 0
        && snapshot.openUntilMs() <= now
        && halfOpenProbing.compareAndSet(false, true)) {
      return true;
    }
    // 快速路径 2：本地已知熔断关闭，且关闭缓存仍有效
    if (snapshot.openUntilMs() == 0 && now < snapshot.closedCacheExpiresAt()) {
      return true;
    }
    // 慢速路径：查询 Redis，原子发布新快照
    Long openUntilMs;
    try {
      openUntilMs =
          redis.evalLong(ALLOW_SCRIPT, BatchRedisKeys.outboxCircuit(), FIELD_OPEN_UNTIL_MS);
    } catch (DataAccessException ex) {
      // Redis 不可达时 fail-open：outbox 事件本就与状态同事务落 PG 不丢,熔断器是纯保护性基础设施,
      // 不应把 Redis 故障放大成投递停摆(与 quota 限流器一致的 fail-open 语义)。回落上次缓存态:
      // 本地已知熔断开→继续拒;否则放行,Redis 恢复后 evalLong 自然重新同步集群态。
      recordFailOpen();
      log.warn(
          "Outbox 熔断器:Redis 不可达,allowNow fail-open 回落缓存态(openUntilMs={}):{}",
          snapshot.openUntilMs(),
          ex.getMessage());
      return snapshot.openUntilMs() <= now;
    }
    // 区分 Redis 返回 0(正常关闭) 与 null(Lua 脚本返回 nil):null 时使用上次缓存的 state
    if (openUntilMs == null) {
      return snapshot.openUntilMs() <= now;
    }
    long resolvedOpen = openUntilMs;
    state = new CircuitState(resolvedOpen, now + outboxProperties.getPollIntervalMillis());
    return resolvedOpen <= now;
  }

  /** 根据本轮推进结果更新熔断状态，并同步刷新本地缓存。 */
  public void onAdvanceResult(int publishFailed) {
    if (!outboxProperties.isCircuitBreakerEnabled()) {
      return;
    }
    long now = BatchDateTimeSupport.utcEpochMillis();
    long ttlMillis = Math.max(outboxProperties.getCircuitBreakerCooldownMillis(), 60_000L);
    Long openUntilMs;
    try {
      openUntilMs =
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
    } catch (DataAccessException ex) {
      // Redis 不可达时 best-effort：本轮不更新集群熔断态,保留本地 state,重置半开标记避免泄漏,
      // 不向 OutboxPollScheduler 抛异常(否则每轮栽在 Redis 上,把 Redis 故障放大成投递停摆)。
      recordFailOpen();
      log.warn("Outbox 熔断器:Redis 不可达,onAdvanceResult 跳过集群态更新:{}", ex.getMessage());
      halfOpenProbing.compareAndSet(true, false);
      return;
    }
    // T-2：原子发布新快照
    long resolvedOpen = openUntilMs != null ? openUntilMs : 0L;
    state = new CircuitState(resolvedOpen, now + outboxProperties.getPollIntervalMillis());
    // C-5.1: 探测完成后重置半开标记
    if (halfOpenProbing.compareAndSet(true, false)) {
      // 探测成功：cachedOpenUntilMs 已为 0，自然进入关闭状态
      // 探测失败：Redis 脚本已更新 openUntilMs，下次 allowNow 会拒绝
    }
  }
}
