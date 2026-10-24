package io.github.pinpols.batch.trigger.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Quartz 调度实例的部署隔离")
class QuartzMisfireRecoveryConfigurationTest {

  @DisplayName("集群名称由部署命名空间派生")
  @Test
  void shouldDeriveClusterNameFromDeploymentNamespace() {
    assertThat(QuartzMisfireRecoveryConfiguration.schedulerName("batch-blue"))
        .isEqualTo("BatchTriggerScheduler-batch-blue");
  }

  @DisplayName("命名空间为空时保持本地调度器名")
  @Test
  void shouldKeepLocalSchedulerNameWhenNamespaceIsBlank() {
    assertThat(QuartzMisfireRecoveryConfiguration.schedulerName(" "))
        .isEqualTo("BatchTriggerScheduler");
  }
}
