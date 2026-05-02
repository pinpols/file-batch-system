package com.example.batch.orchestrator.scheduler.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.domain.scheduling.ResourceCheck;
import com.example.batch.orchestrator.infrastructure.quota.RedisQuotaRuntimeStateService;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * RedisQuotaRuntimeStateService 单元测试：覆盖 Java 侧的守卫逻辑、fail-open 降级、describe 路径。 Lua 脚本本身的窗口/peak
 * 语义由集成测试在真实 Redis 上验证（{@code RedisQuotaRuntimeStateIntegrationTest}）。
 */
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
    service =
        new RedisQuotaRuntimeStateService(
            redis, new BatchTimezoneProvider(new BatchTimezoneProperties()));
  }

  // ── 守卫条件：缺字段 / baseCap<=0 → 直通放行（不应触达 Redis）

  @Test
  void shouldAllowWhenTenantIdBlankWithoutTouchingRedis() {
    ResourceCheck result =
        service.evaluateAndReserve(
            new QuotaRuntimeStateService.QuotaReservationRequest(
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
  void shouldAllowWhenBaseCapZero() {
    ResourceCheck result =
        service.evaluateAndReserve(reservation("t1", "JOB", "j1", "SLIDING_WINDOW", 0, 5, 0));
    assertThat(result.allowed()).isTrue();
  }

  // ── NONE / 0 burst → 直接 Java 比较，不触达 Redis

  @Test
  void shouldBlockWhenNonePolicyAndOverCap() {
    ResourceCheck result =
        service.evaluateAndReserve(reservation("t1", "JOB", "j1", "NONE", 10, 0, 10));
    assertThat(result.allowed()).isFalse();
  }

  @Test
  void shouldAllowWhenNonePolicyAndWithinCap() {
    ResourceCheck result =
        service.evaluateAndReserve(reservation("t1", "JOB", "j1", "NONE", 10, 5, 8));
    assertThat(result.allowed()).isTrue();
  }

  // ── SLIDING_WINDOW + burst：脚本返回 1 → allow；返回 0 → waitForCapacity

  @Test
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

  // ── Redis 故障：fail-open（放行 + WARN）

  @Test
  void shouldFailOpenWhenRedisThrows() {
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
    assertThat(result.allowed()).isTrue();
  }

  // ── describe：空 Hash → 默认快照

  @Test
  void shouldReturnDefaultSnapshotWhenRedisHashIsEmpty() {
    when(redis.entries(anyString())).thenReturn(Map.of());
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        service.describe(
            new QuotaRuntimeStateService.QuotaDescribeRequest(
                new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", "j1"),
                "SLIDING_WINDOW",
                10,
                2));
    assertThat(snap.peakBorrowedCount()).isZero();
    assertThat(snap.remainingBurst()).isEqualTo(10);
  }

  @Test
  void shouldReadPeakBorrowedFromHash() {
    Map<Object, Object> entries = new HashMap<>();
    entries.put("peakBorrowedCount", "4");
    entries.put("windowStartedAt", String.valueOf(System.currentTimeMillis() - 60_000));
    entries.put("windowExpiresAt", String.valueOf(System.currentTimeMillis() + 60_000));
    when(redis.entries(anyString())).thenReturn(entries);
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        service.describe(
            new QuotaRuntimeStateService.QuotaDescribeRequest(
                new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", "j1"),
                "SLIDING_WINDOW",
                10,
                2));
    assertThat(snap.peakBorrowedCount()).isEqualTo(4);
    assertThat(snap.remainingBurst()).isEqualTo(6);
  }

  @Test
  void shouldReturnZeroSnapshotWhenWindowExpired() {
    Map<Object, Object> entries = new HashMap<>();
    entries.put("peakBorrowedCount", "8");
    entries.put("windowStartedAt", String.valueOf(System.currentTimeMillis() - 120_000));
    entries.put("windowExpiresAt", String.valueOf(System.currentTimeMillis() - 60_000));
    when(redis.entries(anyString())).thenReturn(entries);
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        service.describe(
            new QuotaRuntimeStateService.QuotaDescribeRequest(
                new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", "j1"),
                "SLIDING_WINDOW",
                10,
                2));
    assertThat(snap.peakBorrowedCount()).isZero();
    assertThat(snap.remainingBurst()).isEqualTo(10);
  }

  @Test
  void shouldReturnDefaultSnapshotWhenRedisDescribeThrows() {
    when(redis.entries(anyString())).thenThrow(new QueryTimeoutException("down"));
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        service.describe(
            new QuotaRuntimeStateService.QuotaDescribeRequest(
                new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", "j1"),
                "SLIDING_WINDOW",
                10,
                2));
    assertThat(snap.peakBorrowedCount()).isZero();
  }

  // ── reconcileExpiredStates：Redis 实现 no-op

  @Test
  void reconcileShouldBeNoOp() {
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
