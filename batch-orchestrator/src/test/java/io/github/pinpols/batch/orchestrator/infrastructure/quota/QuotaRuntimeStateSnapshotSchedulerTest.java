package io.github.pinpols.batch.orchestrator.infrastructure.quota;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import io.github.pinpols.batch.orchestrator.config.QuotaProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.QuotaRuntimeStateMapper;
import io.github.pinpols.batch.orchestrator.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.orchestrator.mapper.TenantQuotaPolicyMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code batch.quota.snapshot.enabled} 开/关态守卫(此前零测试)。
 *
 * <p>关态:{@code snapshot()} 在读取枚举源(租户策略)之前就早退,不做任何快照工作。 开态:进入租户枚举流程(空租户列表下无副作用,只验证确实调用了枚举源)。 用纯
 * Mockito 单测,不起 Spring —— gate 语义与 DB / Redis 无关。
 */
@ExtendWith(MockitoExtension.class)
class QuotaRuntimeStateSnapshotSchedulerTest {

  @Mock private QuotaRuntimeStateService quotaRuntimeStateService;
  @Mock private QuotaRuntimeStateMapper quotaRuntimeStateMapper;
  @Mock private TenantQuotaPolicyMapper tenantQuotaPolicyMapper;
  @Mock private ResourceQueueMapper resourceQueueMapper;
  @Mock private OrchestratorGracefulShutdown gracefulShutdown;

  private QuotaRuntimeStateSnapshotScheduler scheduler(boolean snapshotEnabled) {
    QuotaProperties quotaProperties = new QuotaProperties();
    quotaProperties.getSnapshot().setEnabled(snapshotEnabled);
    return new QuotaRuntimeStateSnapshotScheduler(
        quotaRuntimeStateService,
        quotaRuntimeStateMapper,
        tenantQuotaPolicyMapper,
        resourceQueueMapper,
        quotaProperties,
        gracefulShutdown);
  }

  @Test
  @DisplayName("关态:snapshot.enabled=false → 早退,不枚举租户、不写任何快照")
  void disabled_shortCircuitsBeforeEnumeratingTenants() {
    when(gracefulShutdown.isDraining()).thenReturn(false);

    scheduler(false).snapshot();

    verify(tenantQuotaPolicyMapper, never()).selectDistinctEnabledTenantIds();
    verify(quotaRuntimeStateMapper, never()).insert(any());
  }

  @Test
  @DisplayName("开态:snapshot.enabled=true → 进入租户枚举流程")
  void enabled_enumeratesTenants() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(tenantQuotaPolicyMapper.selectDistinctEnabledTenantIds()).thenReturn(List.of());

    scheduler(true).snapshot();

    verify(tenantQuotaPolicyMapper).selectDistinctEnabledTenantIds();
  }
}
