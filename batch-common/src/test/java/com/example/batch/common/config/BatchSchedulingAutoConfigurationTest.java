package com.example.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ErrorHandler;

class BatchSchedulingAutoConfigurationTest {

  @Test
  void taskSchedulerUsesExplicitHighPhaseAndShutdownDrainDefaults() {
    BatchSchedulingProperties properties = new BatchSchedulingProperties();
    @SuppressWarnings("unchecked")
    ObjectProvider<ErrorHandler> errorHandlerProvider = mock(ObjectProvider.class);

    TaskScheduler taskScheduler =
        new BatchSchedulingAutoConfiguration().taskScheduler(properties, errorHandlerProvider);

    assertThat(properties.getPhase()).isEqualTo(1_073_741_823);
    assertThat(properties.isWaitForTasksToCompleteOnShutdown()).isTrue();
    assertThat(properties.getAwaitTerminationSeconds()).isEqualTo(120);
    assertThat(taskScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
    assertThat(((ThreadPoolTaskScheduler) taskScheduler).getPhase())
        .isEqualTo(properties.getPhase());
  }
}
