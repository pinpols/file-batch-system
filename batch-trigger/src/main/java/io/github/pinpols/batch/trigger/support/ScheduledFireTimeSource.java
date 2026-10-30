package io.github.pinpols.batch.trigger.support;

import java.time.Instant;
import javax.annotation.Nullable;

/** 计划触发时间源；没有后续触发时刻时返回 null，不携带调度引擎类型。 */
@FunctionalInterface
public interface ScheduledFireTimeSource {
  @Nullable
  Instant nextAfter(Instant afterExclusive);
}
