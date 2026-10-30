package io.github.pinpols.batch.orchestrator.infrastructure.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.config.OutboxProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

/** T-1: OutboxPublishCircuitBreaker 测试——验证 CLOSED → OPEN → HALF_OPEN → CLOSED 状态机。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("出站事件投递熔断器:冷却恢复与探测成败下的集群熔断状态流转,缓存不可达时的放行与降级,以及打开态指标计数")
class OutboxPublishCircuitBreakerTest {

  @Mock
  private BatchOrchestratorGovernanceProperties governance;

  @Mock
  private OrchestratorRedisSupport redis;

  private OutboxPublishCircuitBreaker breaker;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    OutboxProperties props = new OutboxProperties();
    props.setCircuitBreakerEnabled(true);
    props.setCircuitBreakerFailureThresholdConsecutivePolls(3);
    props.setCircuitBreakerCooldownMillis(5000L);
    props.setPollIntervalMillis(1000L);
    when(governance.outbox()).thenReturn(props);
    meterRegistry = new SimpleMeterRegistry();
    breaker = new OutboxPublishCircuitBreaker(governance, redis, meterRegistry);
    breaker.initMetrics();
  }

  @Test
  @DisplayName("熔断开关未启用时直接放行投递,不读取集群熔断状态")
  void shouldAllowWhenCircuitBreakerDisabled() {
    OutboxProperties props = new OutboxProperties();
    props.setCircuitBreakerEnabled(false);
    when(governance.outbox()).thenReturn(props);
    breaker = new OutboxPublishCircuitBreaker(governance, redis, meterRegistry);

    assertThat(breaker.allowNow()).isTrue();
  }

  @Test
  @DisplayName("集群熔断状态为闭合时允许本轮投递并缓存该状态")
  void shouldAllowWhenRedisReturnsClosed() {
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(0L);

    assertThat(breaker.allowNow()).isTrue();
  }

  @Test
  @DisplayName("集群熔断处于打开且冷却未到期时拒绝本轮投递,连续调用沿用本地缓存状态")
  void shouldDenyWhenRedisReturnsOpenUntilFuture() {
    long futureMs = BatchDateTimeSupport.utcEpochMillis() + 60_000;
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(futureMs);

    // 首次调用从 Redis 读取并缓存断路器开启状态。
    assertThat(breaker.allowNow()).isFalse();
    // 第二次调用使用缓存值。
    assertThat(breaker.allowNow()).isFalse();
  }

  @Test
  @DisplayName("冷却期已过时放行一次探测请求,推进到半开状态")
  void shouldTransitionToHalfOpenAfterCooldown() {
    // 模拟断路器已开启，但冷却时间已经结束。
    long pastMs = BatchDateTimeSupport.utcEpochMillis() - 1000;
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(pastMs);

    // 首次调用从 Redis 读到已过期的 openUntilMs，触发半开状态。
    boolean firstCall = breaker.allowNow();
    // Redis 返回已过期的 pastMs 后，allowNow 应放行并将断路器转为半开状态。
    assertThat(firstCall).isTrue();
  }

  @Test
  @DisplayName("半开探测成功时清空连续失败计数并恢复闭合状态")
  void onAdvanceResult_shouldResetHalfOpenProbeOnSuccess() {
    long pastMs = BatchDateTimeSupport.utcEpochMillis() - 1000;
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(pastMs);

    // Enter half-open
    breaker.allowNow();

    // 探测成功后推进状态，失败计数为 0。
    when(redis.evalLong(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(0L);
    breaker.onAdvanceResult(0);

    // 成功探测已更新本地关闭缓存，后续放行不需要再读 Redis。
    assertThat(breaker.allowNow()).isTrue();
  }

  @Test
  @DisplayName("已缓存的打开窗口内直接拒绝,不重复读取集群状态")
  void shouldKeepOpenState_whenCacheHasNotExpired() {
    // First: simulate Redis open state cached
    long futureMs = BatchDateTimeSupport.utcEpochMillis() + 60_000;
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(futureMs);
    assertThat(breaker.allowNow()).isFalse();

    // 本地打开缓存仍在有效期内，连续调用应直接拒绝而不再次访问 Redis。
    assertThat(breaker.allowNow()).isFalse();
    verify(redis, times(1)).evalLong(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("半开探测失败时重新打开熔断,冷却期内继续拒绝投递")
  void onAdvanceResult_shouldReOpenOnProbeFailure() {
    long pastMs = BatchDateTimeSupport.utcEpochMillis() - 1000;
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(pastMs);

    // Enter half-open
    breaker.allowNow();

    // 探测失败后推进状态，并增加失败计数。
    long futureMs = BatchDateTimeSupport.utcEpochMillis() + 60_000;
    when(redis.evalLong(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(futureMs);
    breaker.onAdvanceResult(3);

    // 断路器应再次开启。
    assertThat(breaker.allowNow()).isFalse();
  }

  @Test
  @DisplayName("缓存不可达且本地无已知打开状态时放行投递,避免事件投递整体停摆")
  void allowNow_shouldFailOpen_whenRedisConnectionFails() {
    // Redis 不可达(慢速路径 evalLong 抛连接异常)→ 无本地已知开态时 fail-open 放行。
    // outbox 事件已与状态同事务落 PG,熔断器不应把 Redis 故障放大成投递停摆。
    when(redis.evalLong(anyString(), anyString(), anyString()))
        .thenThrow(new RedisConnectionFailureException("redis down"));

    assertThat(breaker.allowNow()).isTrue();
  }

  @Test
  @DisplayName("缓存不可达时跳过本轮集群状态更新,且不向调用方抛出异常")
  void onAdvanceResult_shouldNotThrow_whenRedisConnectionFails() {
    // best-effort:Redis 不可达时本轮跳过集群态更新,绝不把异常抛回 OutboxPollScheduler
    // (否则每轮栽在 Redis 上,outbox→Kafka 投递停摆)。
    when(redis.evalLong(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new RedisConnectionFailureException("redis down"));

    assertThatCode(() -> breaker.onAdvanceResult(2)).doesNotThrowAnyException();
  }

  // ─── O1: cluster-wide 熔断可观测 ──────────────────────────────────────────

  @Test
  @DisplayName("熔断闭合时集群打开状态指标取值为零")
  void openGauge_isZero_whenCircuitClosed() {
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(0L);
    breaker.allowNow();
    assertThat(meterRegistry.get("batch.outbox.circuit.open").gauge().value()).isZero();
  }

  @Test
  @DisplayName("熔断打开时集群打开状态指标取值为一")
  void openGauge_isOne_whenCircuitOpen() {
    // 熔断打开(openUntilMs 在未来):首个 allowNow 走慢速路径查 Redis → 发布 open 快照 → gauge = 1
    long futureMs = BatchDateTimeSupport.utcEpochMillis() + 60_000;
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(futureMs);
    breaker.allowNow();
    assertThat(meterRegistry.get("batch.outbox.circuit.open").gauge().value()).isEqualTo(1.0d);
  }

  @Test
  @DisplayName("缓存不可达时放行判断与状态推进两条路径各累计一次降级放行计数")
  void failopenCounter_incrementsOnRedisFailureInBothPaths() {
    when(redis.evalLong(anyString(), anyString(), anyString()))
        .thenThrow(new RedisConnectionFailureException("redis down"));
    when(redis.evalLong(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new RedisConnectionFailureException("redis down"));

    breaker.allowNow(); // allowNow fail-open 分支
    breaker.onAdvanceResult(1); // onAdvanceResult fail-open 分支

    assertThat(
            meterRegistry.get("batch.outbox.circuit.failopen.total").counter().count())
        .isEqualTo(2.0d);
  }
}
