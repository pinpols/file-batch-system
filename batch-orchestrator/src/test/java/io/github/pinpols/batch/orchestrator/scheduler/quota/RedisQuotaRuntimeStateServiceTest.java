package io.github.pinpols.batch.orchestrator.scheduler.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import io.github.pinpols.batch.orchestrator.config.QuotaProperties;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.infrastructure.quota.RedisQuotaRuntimeStateService;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * RedisQuotaRuntimeStateService 单元测试：覆盖 Java 侧的守卫逻辑、Redis 故障降级、describe 路径。 Lua 脚本本身的窗口/peak
 * 语义由集成测试在真实 Redis 上验证（{@code RedisQuotaRuntimeStateIntegrationTest}）。
 */
@DisplayName("配额运行态服务的本地守卫,Redis 故障降级与运行态快照读取行为验证")
class RedisQuotaRuntimeStateServiceTest {

  private OrchestratorRedisSupport redis;
  private StringRedisTemplate redisTemplate;
  private RedisQuotaRuntimeStateService service;

  @BeforeEach
  void setUp() {
    redis = mock(OrchestratorRedisSupport.class);
    redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    SetOperations<String, String> setOps = mock(SetOperations.class);
    lenient().when(redis.redisTemplate()).thenReturn(redisTemplate);
    lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    service = new RedisQuotaRuntimeStateService(
        redis, new BatchTimezoneProvider(new BatchTimezoneProperties()));
  }

  // ── 守卫条件：缺字段 / baseCap<=0 → 直通放行（不应触达 Redis）

  @Test
  @DisplayName("租户标识为空时直接放行,且完全不触达 Redis 脚本")
  void shouldAllowWhenTenantIdBlankWithoutTouchingRedis() {
    ResourceCheck result =
        service.evaluateAndReserve(new QuotaRuntimeStateService.QuotaReservationRequest(
            new QuotaRuntimeStateService.QuotaReservationOwner("", "JOB", "j1"),
            new QuotaRuntimeStateService.QuotaReservationPolicy("SLIDING_WINDOW", 10, 5, 2),
            0,
            1,
            new QuotaRuntimeStateService.QuotaReservationReason("OVER", "over")));
    assertThat(result.allowed()).isTrue();
    verify(redis, never())
        .evalList(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString());
  }

  @Test
  @DisplayName("基础配额为零时直接放行")
  void shouldAllowWhenBaseCapZero() {
    ResourceCheck result =
        service.evaluateAndReserve(reservation("t1", "JOB", "j1", "SLIDING_WINDOW", 0, 5, 0));
    assertThat(result.allowed()).isTrue();
  }

  // ── NONE / 0 burst → 直接 Java 比较，不触达 Redis

  @Test
  @DisplayName("无窗口策略下在用量超过基础配额时拒绝放行")
  void shouldBlockWhenNonePolicyAndOverCap() {
    ResourceCheck result =
        service.evaluateAndReserve(reservation("t1", "JOB", "j1", "NONE", 10, 0, 10));
    assertThat(result.allowed()).isFalse();
  }

  @Test
  @DisplayName("无窗口策略下在用量未超过基础配额时允许放行")
  void shouldAllowWhenNonePolicyAndWithinCap() {
    ResourceCheck result =
        service.evaluateAndReserve(reservation("t1", "JOB", "j1", "NONE", 10, 5, 8));
    assertThat(result.allowed()).isTrue();
  }

  // ── SLIDING_WINDOW + burst：脚本返回 1 → allow；返回 0 → waitForCapacity

  @Test
  @DisplayName("滑动窗口策略下脚本判定允许时放行")
  void shouldAllowWhenLuaReturnsAllowed() {
    when(redis.evalList(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(List.<Object>of("1", "3", "0", "0"));
    ResourceCheck result =
        service.evaluateAndReserve(reservation("t1", "JOB", "j1", "SLIDING_WINDOW", 5, 10, 7));
    assertThat(result.allowed()).isTrue();
  }

  @Test
  @DisplayName("滑动窗口策略下脚本判定拒绝时不予放行")
  void shouldBlockWhenLuaReturnsRejected() {
    when(redis.evalList(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(List.<Object>of("0", "10", "0", "0"));
    ResourceCheck result =
        service.evaluateAndReserve(reservation("t1", "JOB", "j1", "SLIDING_WINDOW", 5, 3, 8));
    assertThat(result.allowed()).isFalse();
  }

  // ── Redis 故障：默认 fail-closed；FAIL_OPEN 只能显式配置

  @Test
  @DisplayName("默认故障模式下 Redis 访问超时时判定不放行,保持故障关闭")
  void shouldFailClosedWhenRedisThrowsByDefault() {
    when(redis.evalList(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenThrow(new QueryTimeoutException("redis down"));
    ResourceCheck result =
        service.evaluateAndReserve(reservation("t1", "JOB", "j1", "SLIDING_WINDOW", 5, 3, 8));
    assertThat(result.allowed()).isFalse();
  }

  @Test
  @DisplayName("显式配置故障放行模式时 Redis 访问超时判定放行")
  void shouldFailOpenOnlyWhenExplicitlyConfigured() {
    RedisQuotaRuntimeStateService failOpenService = new RedisQuotaRuntimeStateService(
        redis,
        new BatchTimezoneProvider(new BatchTimezoneProperties()),
        quotaProperties("FAIL_OPEN"));
    when(redis.evalList(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenThrow(new QueryTimeoutException("redis down"));
    assertThat(failOpenService
            .evaluateAndReserve(reservation("t1", "JOB", "j1", "SLIDING_WINDOW", 5, 3, 8))
            .allowed())
        .isTrue();
  }

  // ── describe：空 Hash → 默认快照

  @Test
  @DisplayName("运行态哈希为空时返回默认快照,峰值借用数为零且剩余突发额度等于基础配额")
  void shouldReturnDefaultSnapshotWhenRedisHashIsEmpty() {
    when(redis.entries(anyString())).thenReturn(Map.of());
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        service.describe(new QuotaRuntimeStateService.QuotaDescribeRequest(
            new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", "j1"),
            "SLIDING_WINDOW",
            10,
            2));
    assertThat(snap.peakBorrowedCount()).isZero();
    assertThat(snap.remainingBurst()).isEqualTo(10);
  }

  @Test
  @DisplayName("窗口未过期时从哈希读取峰值借用数,剩余突发额度按基础配额扣减")
  void shouldReadPeakBorrowedFromHash() {
    Map<Object, Object> entries = new HashMap<>();
    entries.put("peakBorrowedCount", "4");
    entries.put("windowStartedAt", String.valueOf(BatchDateTimeSupport.utcEpochMillis() - 60_000));
    entries.put("windowExpiresAt", String.valueOf(BatchDateTimeSupport.utcEpochMillis() + 60_000));
    when(redis.entries(anyString())).thenReturn(entries);
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        service.describe(new QuotaRuntimeStateService.QuotaDescribeRequest(
            new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", "j1"),
            "SLIDING_WINDOW",
            10,
            2));
    assertThat(snap.peakBorrowedCount()).isEqualTo(4);
    assertThat(snap.remainingBurst()).isEqualTo(6);
  }

  @Test
  @DisplayName("窗口已过期时快照归零,峰值借用数为零且剩余突发额度恢复为基础配额")
  void shouldReturnZeroSnapshotWhenWindowExpired() {
    Map<Object, Object> entries = new HashMap<>();
    entries.put("peakBorrowedCount", "8");
    entries.put("windowStartedAt", String.valueOf(BatchDateTimeSupport.utcEpochMillis() - 120_000));
    entries.put("windowExpiresAt", String.valueOf(BatchDateTimeSupport.utcEpochMillis() - 60_000));
    when(redis.entries(anyString())).thenReturn(entries);
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        service.describe(new QuotaRuntimeStateService.QuotaDescribeRequest(
            new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", "j1"),
            "SLIDING_WINDOW",
            10,
            2));
    assertThat(snap.peakBorrowedCount()).isZero();
    assertThat(snap.remainingBurst()).isEqualTo(10);
  }

  @Test
  @DisplayName("查询快照遇 Redis 故障时返回默认快照,峰值借用数为零")
  void shouldReturnDefaultSnapshotWhenRedisDescribeThrows() {
    when(redis.entries(anyString())).thenThrow(new QueryTimeoutException("down"));
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        service.describe(new QuotaRuntimeStateService.QuotaDescribeRequest(
            new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", "j1"),
            "SLIDING_WINDOW",
            10,
            2));
    assertThat(snap.peakBorrowedCount()).isZero();
  }

  // ── reconcileExpiredStates：Redis 实现 no-op

  @Test
  @DisplayName("清理过期运行态时按 Redis 实现保持无操作,不执行任何脚本")
  void shouldBeNoOp_whenReconcilingExpiredStates() {
    service.reconcileExpiredStates(2);
    verify(redis, never())
        .evalList(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString());
  }

  private static QuotaProperties quotaProperties(String failureMode) {
    QuotaProperties properties = new QuotaProperties();
    properties.getRedis().setFailureMode(failureMode);
    return properties;
  }

  private static QuotaRuntimeStateService.QuotaReservationRequest reservation(
      String tenantId,
      String scope,
      String owner,
      String policy,
      int baseCap,
      int burst,
      long active) {
    return new QuotaRuntimeStateService.QuotaReservationRequest(
        new QuotaRuntimeStateService.QuotaReservationOwner(tenantId, scope, owner),
        new QuotaRuntimeStateService.QuotaReservationPolicy(policy, baseCap, burst, 2),
        active,
        1,
        new QuotaRuntimeStateService.QuotaReservationReason("OVER", "over"));
  }
}
