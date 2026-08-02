package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.lifecycle.BatchLifecyclePhases;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ErrorHandler;

class BatchSchedulingAutoConfigurationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void taskSchedulerUsesExplicitHighPhaseAndShutdownDrainDefaults() {
    BatchSchedulingProperties properties = new BatchSchedulingProperties();
    @SuppressWarnings("unchecked")
    ObjectProvider<ErrorHandler> errorHandlerProvider = mock(ObjectProvider.class);

    TaskScheduler taskScheduler =
        new BatchSchedulingAutoConfiguration().taskScheduler(properties, errorHandlerProvider);

    assertThat(properties.getPhase()).isEqualTo(BatchLifecyclePhases.MANAGED_SCHEDULER);
    assertThat(properties.isWaitForTasksToCompleteOnShutdown()).isTrue();
    assertThat(properties.getAwaitTerminationSeconds()).isEqualTo(120);
    assertThat(taskScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
    assertThat(((ThreadPoolTaskScheduler) taskScheduler).getPhase())
        .isEqualTo(properties.getPhase());
  }

  @Test
  void rejectsInvalidSchedulingBoundsBeforeSchedulerCreation() {
    BatchSchedulingProperties properties = new BatchSchedulingProperties();
    properties.setPoolSize(0);
    properties.setThreadNamePrefix(" ");
    properties.setAwaitTerminationSeconds(-1);

    assertThat(validator.validate(properties))
        .extracting(Object::toString)
        .anyMatch(message -> message.contains("pool-size"))
        .anyMatch(message -> message.contains("thread-name-prefix"))
        .anyMatch(message -> message.contains("await-termination-seconds"));
  }
}
