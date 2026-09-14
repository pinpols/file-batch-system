package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class ResourceAdmissionStartupGuardTest {

  private static final DefaultApplicationArguments NO_ARGS = new DefaultApplicationArguments();

  @Test
  void productionRejectsUnboundedGlobalAdmission() {
    ResourceSchedulerProperties properties = new ResourceSchedulerProperties();
    properties.setGlobalMaxRunningJobs(0);
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");

    ResourceAdmissionStartupGuard guard =
        new ResourceAdmissionStartupGuard(environment, properties);

    assertThatIllegalStateException().isThrownBy(() -> guard.run(NO_ARGS));
  }

  @Test
  void productionAcceptsPositiveGlobalAdmissionCap() {
    ResourceSchedulerProperties properties = new ResourceSchedulerProperties();
    properties.setGlobalMaxRunningJobs(1000);
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");

    ResourceAdmissionStartupGuard guard =
        new ResourceAdmissionStartupGuard(environment, properties);

    assertThatNoException().isThrownBy(() -> guard.run(NO_ARGS));
  }

  @Test
  void localMayDisableGlobalAdmissionCap() {
    ResourceSchedulerProperties properties = new ResourceSchedulerProperties();
    properties.setGlobalMaxRunningJobs(0);
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("local");

    ResourceAdmissionStartupGuard guard =
        new ResourceAdmissionStartupGuard(environment, properties);

    assertThatNoException().isThrownBy(() -> guard.run(NO_ARGS));
  }
}
