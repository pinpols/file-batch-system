package io.github.pinpols.batch.trigger.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.lifecycle.BatchLifecyclePhases;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class QuartzTriggerConfigurationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void triggerOutboxRelaySchedulerStopsBeforeRedisWithoutDrainingPolls() {
    TriggerOutboxRelayProperties properties = new TriggerOutboxRelayProperties();

    ThreadPoolTaskScheduler scheduler =
        new QuartzTriggerConfiguration().triggerOutboxRelayScheduler(properties);

    assertThat(properties.isWaitForTasksToCompleteOnShutdown()).isFalse();
    assertThat(properties.getSchedulerPhase()).isEqualTo(BatchLifecyclePhases.MANAGED_SCHEDULER);
    assertThat(scheduler.getPhase()).isEqualTo(properties.getSchedulerPhase());
  }

  @Test
  void rejectsInvalidRelayBoundsBeforeScheduling() {
    TriggerOutboxRelayProperties properties = new TriggerOutboxRelayProperties();
    properties.setPollIntervalMillis(0);
    properties.setBatchSize(0);
    properties.setPublishingTimeoutSeconds(0);
    properties.setMaxPublishAttempts(0);
    properties.setShutdownAwaitSeconds(-1);

    assertThat(validator.validate(properties))
        .extracting(Object::toString)
        .anyMatch(message -> message.contains("poll-interval-millis"))
        .anyMatch(message -> message.contains("batch-size"))
        .anyMatch(message -> message.contains("publishing-timeout-seconds"))
        .anyMatch(message -> message.contains("max-publish-attempts"))
        .anyMatch(message -> message.contains("shutdown-await-seconds"));
  }
}
