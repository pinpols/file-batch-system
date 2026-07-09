package io.github.pinpols.batch.orchestrator.application.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.config.RateLimitProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证 action→配额映射与短路放行逻辑（防 TASK_* 误接到错误阈值键）+ 拒绝计数。 */
@ExtendWith(MockitoExtension.class)
class TenantActionRateLimiterTest {

  @Mock private BatchOrchestratorGovernanceProperties governance;
  @Mock private TokenBucketRateLimiter limiter;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private TenantActionRateLimiter newLimiter(RateLimitProperties props) {
    when(governance.rateLimit()).thenReturn(props);
    return new TenantActionRateLimiter(governance, limiter, meterRegistry);
  }

  @Test
  @DisplayName("TASK_CLAIM 用 claim 阈值,TASK_REPORT 用 report 阈值")
  void taskActionsResolveToTheirOwnQuota() {
    RateLimitProperties props = new RateLimitProperties();
    props.setMaxClaimRequestsPerTenantPerMinute(111L);
    props.setMaxReportRequestsPerTenantPerMinute(222L);
    TenantActionRateLimiter rl = newLimiter(props);
    when(limiter.tryConsume(eq("t1"), eq("TASK_CLAIM"), anyLong())).thenReturn(true);
    when(limiter.tryConsume(eq("t1"), eq("TASK_REPORT"), anyLong())).thenReturn(true);

    rl.tryConsume("t1", RateLimitAction.TASK_CLAIM);
    rl.tryConsume("t1", RateLimitAction.TASK_REPORT);

    ArgumentCaptor<Long> claimMax = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<Long> reportMax = ArgumentCaptor.forClass(Long.class);
    verify(limiter).tryConsume(eq("t1"), eq("TASK_CLAIM"), claimMax.capture());
    verify(limiter).tryConsume(eq("t1"), eq("TASK_REPORT"), reportMax.capture());
    assertThat(claimMax.getValue()).isEqualTo(111L);
    assertThat(reportMax.getValue()).isEqualTo(222L);
  }

  @Test
  @DisplayName("命中限流拒绝时按 action 自增 batch.ratelimit.rejected.total,放行时不计数")
  void rejectionIncrementsCounterByAction() {
    RateLimitProperties props = new RateLimitProperties();
    props.setMaxClaimRequestsPerTenantPerMinute(1L);
    props.setMaxReportRequestsPerTenantPerMinute(1L);
    TenantActionRateLimiter rl = newLimiter(props);
    when(limiter.tryConsume(eq("t1"), eq("TASK_CLAIM"), anyLong())).thenReturn(false);
    when(limiter.tryConsume(eq("t1"), eq("TASK_REPORT"), anyLong())).thenReturn(true);

    assertThat(rl.tryConsume("t1", RateLimitAction.TASK_CLAIM)).isFalse();
    assertThat(rl.tryConsume("t1", RateLimitAction.TASK_REPORT)).isTrue();

    assertThat(
            meterRegistry
                .get(TenantActionRateLimiter.METRIC_REJECTED)
                .tag("action", "TASK_CLAIM")
                .counter()
                .count())
        .isEqualTo(1.0);
    // 放行的 action 不建计数器
    assertThat(
            meterRegistry
                .find(TenantActionRateLimiter.METRIC_REJECTED)
                .tag("action", "TASK_REPORT")
                .counter())
        .isNull();
  }

  @Test
  @DisplayName("总开关关闭时直接放行,不触达底层限流器")
  void disabledShortCircuits() {
    RateLimitProperties props = new RateLimitProperties();
    props.setEnabled(false);
    TenantActionRateLimiter rl = newLimiter(props);

    assertThat(rl.tryConsume("t1", RateLimitAction.TASK_CLAIM)).isTrue();
    verifyNoInteractions(limiter);
  }

  @Test
  @DisplayName("tenantId 为空时放行(无法归属租户的内部调用)")
  void blankTenantShortCircuits() {
    TenantActionRateLimiter rl = newLimiter(new RateLimitProperties());

    assertThat(rl.tryConsume(null, RateLimitAction.TASK_CLAIM)).isTrue();
    assertThat(rl.tryConsume("  ", RateLimitAction.TASK_REPORT)).isTrue();
    verifyNoInteractions(limiter);
  }
}
