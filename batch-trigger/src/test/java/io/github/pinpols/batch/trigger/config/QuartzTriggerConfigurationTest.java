package io.github.pinpols.batch.trigger.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.lifecycle.BatchLifecyclePhases;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@DisplayName("Quartz trigger 配置:outbox relay 调度器的关停阶段与 outbox 参数的校验边界")
class QuartzTriggerConfigurationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  @DisplayName("relay 调度器取托管调度阶段且关闭不等待任务完成,确保先于 Redis 停机")
  void relayScheduler_stopsBeforeRedisWithoutDrainingPolls() {
    TriggerOutboxRelayProperties properties = new TriggerOutboxRelayProperties();

    ThreadPoolTaskScheduler scheduler =
        new QuartzTriggerConfiguration().triggerOutboxRelayScheduler(properties);

    assertThat(properties.isWaitForTasksToCompleteOnShutdown()).isFalse();
    assertThat(properties.getSchedulerPhase()).isEqualTo(BatchLifecyclePhases.MANAGED_SCHEDULER);
    assertThat(scheduler.getPhase()).isEqualTo(properties.getSchedulerPhase());
  }

  @Test
  @DisplayName("poll 间隔、批大小、发布超时、最大重试与关停等待取 0 或负数时逐项校验拒绝")
  void invalidRelayBounds_areRejectedBeforeScheduling() {
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

  @Test
  @DisplayName("开启自适应释放后最小速率高于最大速率且 lag 软硬阈值相等时判定配置不一致")
  void inconsistentAdaptiveRelease_isRejected() {
    TriggerOutboxRelayProperties properties = new TriggerOutboxRelayProperties();
    properties.setAdaptiveReleaseEnabled(true);
    properties.setMaxPublishEventsPerSecond(10);
    properties.setMinPublishEventsPerSecond(11);
    properties.setLagSoftThreshold(500);
    properties.setLagHardThreshold(500);

    assertThat(validator.validate(properties))
        .extracting(Object::toString)
        .anyMatch(message -> message.contains("adaptive release thresholds are inconsistent"));
  }
}
