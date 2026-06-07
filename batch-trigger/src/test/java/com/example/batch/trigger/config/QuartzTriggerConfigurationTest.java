package com.example.batch.trigger.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class QuartzTriggerConfigurationTest {

  @Test
  void triggerOutboxRelaySchedulerStopsBeforeRedisWithoutDrainingPolls() {
    TriggerOutboxRelayProperties properties = new TriggerOutboxRelayProperties();

    ThreadPoolTaskScheduler scheduler =
        new QuartzTriggerConfiguration().triggerOutboxRelayScheduler(properties);

    assertThat(properties.isWaitForTasksToCompleteOnShutdown()).isFalse();
    assertThat(properties.getSchedulerPhase()).isEqualTo(1_073_741_823);
    assertThat(scheduler.getPhase()).isEqualTo(properties.getSchedulerPhase());
  }
}
