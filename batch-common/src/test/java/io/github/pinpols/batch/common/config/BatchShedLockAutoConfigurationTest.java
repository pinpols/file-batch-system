package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BatchShedLockAutoConfigurationTest {

  @Test
  void shouldPrefixServiceLockNamespaceWithDeploymentNamespace() {
    assertThat(BatchShedLockAutoConfiguration.redisEnvironmentName(
            "batch-trigger", "batch-trigger", "blue"))
        .isEqualTo("blue:batch-trigger");
  }

  @Test
  void shouldRetainServiceLockNamespaceOutsideNamespacedDeployment() {
    assertThat(BatchShedLockAutoConfiguration.redisEnvironmentName(
            "batch-trigger", "batch-trigger", " "))
        .isEqualTo("batch-trigger");
  }

  @Test
  void shouldUseApplicationNameWhenLockNamespaceIsNotConfigured() {
    assertThat(BatchShedLockAutoConfiguration.redisEnvironmentName("", "batch-trigger", "blue"))
        .isEqualTo("blue:batch-trigger");
  }
}
