package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.service.asset.AssetFreshnessPolicyService;
import io.github.pinpols.batch.orchestrator.config.AssetFreshnessPolicyProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("资产新鲜度策略定时扫描:开关状态与停机排空的门控,以及正常运行时的批量委派")
class AssetFreshnessPolicySchedulerTest {

  private AssetFreshnessPolicyService freshnessPolicyService;
  private AssetFreshnessPolicyProperties properties;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private AssetFreshnessPolicyScheduler scheduler;

  @BeforeEach
  void setUp() {
    freshnessPolicyService = mock(AssetFreshnessPolicyService.class);
    gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    properties = new AssetFreshnessPolicyProperties();
    properties.setEnabled(true);
    properties.setBatchLimit(25);
    scheduler =
        new AssetFreshnessPolicyScheduler(freshnessPolicyService, properties, gracefulShutdown);
  }

  @Test
  @DisplayName("策略开关关闭时跳过扫描,不触发到期策略处理")
  void shouldSkipScan_whenPolicyDisabled() {
    properties.setEnabled(false);

    scheduler.scan();

    verify(freshnessPolicyService, never()).scanDuePolicies(25);
  }

  @Test
  @DisplayName("停机排空期间跳过扫描,不触发到期策略处理")
  void shouldSkipScan_whenShutdownDraining() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    scheduler.scan();

    verify(freshnessPolicyService, never()).scanDuePolicies(25);
  }

  @Test
  @DisplayName("开关开启且未在排空时按配置的批量上限委派扫描")
  void shouldDelegateScanWithConfiguredLimit_whenEnabledAndNotDraining() {
    when(gracefulShutdown.isDraining()).thenReturn(false);

    scheduler.scan();

    verify(freshnessPolicyService).scanDuePolicies(25);
  }
}
