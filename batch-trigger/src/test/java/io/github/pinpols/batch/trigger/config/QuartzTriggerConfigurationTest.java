package io.github.pinpols.batch.trigger.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.lifecycle.BatchLifecyclePhases;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class QuartzTriggerConfigurationTest {

  @Test
  void triggerOutboxRelaySchedulerStopsBeforeRedisWithoutDrainingPolls() {
    TriggerOutboxRelayProperties properties = new TriggerOutboxRelayProperties();

    ThreadPoolTaskScheduler scheduler =
        new QuartzTriggerConfiguration().triggerOutboxRelayScheduler(properties);

    assertThat(properties.isWaitForTasksToCompleteOnShutdown()).isFalse();
    assertThat(properties.getSchedulerPhase()).isEqualTo(BatchLifecyclePhases.MANAGED_SCHEDULER);
    assertThat(scheduler.getPhase()).isEqualTo(properties.getSchedulerPhase());
  }
}
