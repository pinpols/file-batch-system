package io.github.pinpols.batch.common.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("生命周期停机阶段:调度与基础设施客户端的停止先后顺序约束")
class BatchLifecyclePhasesTest {

  @Test
  @DisplayName("停机阶段取值保证中继与工作器客户端先于调度器和基础设施客户端停止")
  void shouldStopSchedulersBeforeInfrastructureClients_whenShuttingDown() {
    assertThat(BatchLifecyclePhases.FIRST_TO_STOP_RELAY)
        .isGreaterThan(BatchLifecyclePhases.WORKER_SDK_CLIENT);
    assertThat(BatchLifecyclePhases.WORKER_SDK_CLIENT)
        .isGreaterThan(BatchLifecyclePhases.MANAGED_SCHEDULER);
    assertThat(BatchLifecyclePhases.MANAGED_SCHEDULER)
        .isGreaterThan(BatchLifecyclePhases.INFRASTRUCTURE_CLIENT_DEFAULT);
  }
}
