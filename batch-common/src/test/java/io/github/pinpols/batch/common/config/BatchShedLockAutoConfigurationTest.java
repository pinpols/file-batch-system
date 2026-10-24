package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("调度锁的部署命名空间配置")
class BatchShedLockAutoConfigurationTest {

  @DisplayName("服务锁使用部署命名空间前缀")
  @Test
  void shouldPrefixServiceLockNamespaceWithDeploymentNamespace() {
    assertThat(BatchShedLockAutoConfiguration.redisEnvironmentName(
            "batch-trigger", "batch-trigger", "blue"))
        .isEqualTo("blue:batch-trigger");
  }

  @DisplayName("非命名空间部署保留服务锁名称")
  @Test
  void shouldRetainServiceLockNamespaceOutsideNamespacedDeployment() {
    assertThat(BatchShedLockAutoConfiguration.redisEnvironmentName(
            "batch-trigger", "batch-trigger", " "))
        .isEqualTo("batch-trigger");
  }

  @DisplayName("未配置锁命名空间时使用应用名")
  @Test
  void shouldUseApplicationNameWhenLockNamespaceIsNotConfigured() {
    assertThat(BatchShedLockAutoConfiguration.redisEnvironmentName("", "batch-trigger", "blue"))
        .isEqualTo("blue:batch-trigger");
  }
}
