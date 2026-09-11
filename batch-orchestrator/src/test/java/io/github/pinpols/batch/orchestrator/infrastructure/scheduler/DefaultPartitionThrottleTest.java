package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 单元测试：关闭分区配额时必须短路，避免高吞吐 launch 对活跃分区表执行无效 COUNT。 */
@ExtendWith(MockitoExtension.class)
class DefaultPartitionThrottleTest {

  @Mock
  private JobPartitionMapper jobPartitionMapper;

  @Mock
  private OrchestratorConfigCacheService configCacheService;

  @Mock
  private QuotaRuntimeStateService quotaRuntimeStateService;

  @Mock
  private BatchOrchestratorGovernanceProperties governance;

  private DefaultPartitionThrottle throttle;

  @BeforeEach
  void setUp() {
    throttle = new DefaultPartitionThrottle(
        jobPartitionMapper, configCacheService, quotaRuntimeStateService, governance);
  }

  @Test
  @DisplayName("租户和队列分区配额均关闭 → 直接放行且不查询活跃分区")
  void disabledPartitionQuotasSkipActivePartitionCount() {
    ResourceSchedulingRequest request = new ResourceSchedulingRequest();
    request.setTenantId("ta");
    TenantQuotaPolicyEntity disabledLimits = new TenantQuotaPolicyEntity(
        null, "ta", "unbounded", 0, 0, 0, 1, null, 0, 0, "NONE", 0, true, null);
    when(configCacheService.findEnabledQuotaPolicy("ta")).thenReturn(disabledLimits);

    ResourceCheck result = throttle.check(request, null);

    assertThat(result.allowed()).isTrue();
    verifyNoInteractions(jobPartitionMapper, quotaRuntimeStateService, governance);
  }
}
