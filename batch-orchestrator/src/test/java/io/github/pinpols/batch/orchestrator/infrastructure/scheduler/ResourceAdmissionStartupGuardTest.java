package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("资源配置准入的启动自检:生产环境必须设置正的全局并发上限,本地环境允许关闭该上限")
class ResourceAdmissionStartupGuardTest {

  private static final DefaultApplicationArguments NO_ARGS = new DefaultApplicationArguments();

  @Test
  @DisplayName("生产环境全局并发上限为零时拒绝启动,避免无界放量")
  void shouldRejectStartup_whenProductionGlobalCapUnbounded() {
    ResourceSchedulerProperties properties = new ResourceSchedulerProperties();
    properties.setGlobalMaxRunningJobs(0);
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");

    ResourceAdmissionStartupGuard guard =
        new ResourceAdmissionStartupGuard(environment, properties);

    assertThatIllegalStateException().isThrownBy(() -> guard.run(NO_ARGS));
  }

  @Test
  @DisplayName("生产环境配置正的全局并发上限时启动放行")
  void shouldStartNormally_whenProductionGlobalCapPositive() {
    ResourceSchedulerProperties properties = new ResourceSchedulerProperties();
    properties.setGlobalMaxRunningJobs(1000);
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");

    ResourceAdmissionStartupGuard guard =
        new ResourceAdmissionStartupGuard(environment, properties);

    assertThatNoException().isThrownBy(() -> guard.run(NO_ARGS));
  }

  @Test
  @DisplayName("本地环境关闭全局并发上限时启动放行")
  void shouldStartNormally_whenLocalProfileGlobalCapDisabled() {
    ResourceSchedulerProperties properties = new ResourceSchedulerProperties();
    properties.setGlobalMaxRunningJobs(0);
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("local");

    ResourceAdmissionStartupGuard guard =
        new ResourceAdmissionStartupGuard(environment, properties);

    assertThatNoException().isThrownBy(() -> guard.run(NO_ARGS));
  }
}
