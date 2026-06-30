package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.service.asset.AssetFreshnessPolicyService;
import io.github.pinpols.batch.orchestrator.config.AssetFreshnessPolicyProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AssetFreshnessPolicySchedulerTest {

  private AssetFreshnessPolicyService freshnessPolicyService;
  private AssetFreshnessPolicyProperties properties;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private AssetFreshnessPolicyScheduler scheduler;

  @BeforeEach
  void setUp() {
    freshnessPolicyService = Mockito.mock(AssetFreshnessPolicyService.class);
    gracefulShutdown = Mockito.mock(OrchestratorGracefulShutdown.class);
    properties = new AssetFreshnessPolicyProperties();
    properties.setEnabled(true);
    properties.setBatchLimit(25);
    scheduler =
        new AssetFreshnessPolicyScheduler(freshnessPolicyService, properties, gracefulShutdown);
  }

  @Test
  void scanSkipsWhenDisabled() {
    properties.setEnabled(false);

    scheduler.scan();

    verify(freshnessPolicyService, never()).scanDuePolicies(25);
  }

  @Test
  void scanSkipsWhenDraining() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    scheduler.scan();

    verify(freshnessPolicyService, never()).scanDuePolicies(25);
  }

  @Test
  void scanDelegatesWithConfiguredLimit() {
    when(gracefulShutdown.isDraining()).thenReturn(false);

    scheduler.scan();

    verify(freshnessPolicyService).scanDuePolicies(25);
  }
}
