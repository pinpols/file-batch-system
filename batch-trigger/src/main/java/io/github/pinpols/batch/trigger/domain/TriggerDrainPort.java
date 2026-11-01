package io.github.pinpols.batch.trigger.domain;

import java.time.Instant;

/** 排水用例所需的调度器操作，屏蔽具体调度器异常。 */
public interface TriggerDrainPort {
  boolean isDraining();

  void enable();

  void disable();

  Status status();

  record Status(boolean draining, Instant drainingSince, String reason, String schedulerStatus) {}
}
