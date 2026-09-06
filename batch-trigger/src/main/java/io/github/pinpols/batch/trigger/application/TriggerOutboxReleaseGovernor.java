package io.github.pinpols.batch.trigger.application;

import io.github.pinpols.batch.trigger.application.TriggerLaunchLagMonitor.LagSnapshot;
import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;

/** 根据最新 Kafka lag 样本，以 AIMD 方式计算单 Trigger 进程的有效发布速率。 */
final class TriggerOutboxReleaseGovernor {

  private final TriggerOutboxRelayProperties properties;
  private final TriggerLaunchLagMonitor lagMonitor;
  private long appliedSequence = Long.MIN_VALUE;
  private int effectiveLimit;

  TriggerOutboxReleaseGovernor(
      TriggerOutboxRelayProperties properties, TriggerLaunchLagMonitor lagMonitor) {
    this.properties = properties;
    this.lagMonitor = lagMonitor;
    this.effectiveLimit = properties.getMaxPublishEventsPerSecond();
  }

  synchronized int effectiveLimit() {
    int configuredLimit = properties.getMaxPublishEventsPerSecond();
    if (!properties.isAdaptiveReleaseEnabled() || configuredLimit <= 0) {
      effectiveLimit = configuredLimit;
      return effectiveLimit;
    }

    LagSnapshot sample = lagMonitor.current();
    if (sample.sequence() == appliedSequence) {
      return effectiveLimit;
    }
    appliedSequence = sample.sequence();
    int minimum = Math.min(properties.getMinPublishEventsPerSecond(), configuredLimit);
    long lag = sample.lag();
    if (lag == TriggerLaunchLagMonitor.UNKNOWN_LAG) {
      effectiveLimit = minimum;
    } else if (lag >= properties.getLagHardThreshold()) {
      effectiveLimit = Math.max(minimum, Math.floorDiv(effectiveLimit + 1, 2));
    } else if (lag >= properties.getLagSoftThreshold()) {
      effectiveLimit = Math.max(minimum, effectiveLimit - Math.max(1, effectiveLimit / 4));
    } else {
      effectiveLimit =
          Math.min(configuredLimit, effectiveLimit + properties.getAdaptiveIncreaseStep());
    }
    return effectiveLimit;
  }
}
