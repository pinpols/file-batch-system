package io.github.pinpols.batch.common.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BatchLifecyclePhasesTest {

  @Test
  void shutdownOrderKeepsSchedulersAheadOfInfrastructureClients() {
    assertThat(BatchLifecyclePhases.FIRST_TO_STOP_RELAY)
        .isGreaterThan(BatchLifecyclePhases.WORKER_SDK_CLIENT);
    assertThat(BatchLifecyclePhases.WORKER_SDK_CLIENT)
        .isGreaterThan(BatchLifecyclePhases.MANAGED_SCHEDULER);
    assertThat(BatchLifecyclePhases.MANAGED_SCHEDULER)
        .isGreaterThan(BatchLifecyclePhases.INFRASTRUCTURE_CLIENT_DEFAULT);
  }
}
