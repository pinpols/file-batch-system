package io.github.pinpols.batch.trigger.infrastructure;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.support.ScheduledFireTimeSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import org.quartz.Trigger;
import org.springframework.stereotype.Component;

/** 在指定时间区间内有界枚举计划触发时刻，拒绝不前进的时间源及超量计划。 */
@Component
public class QuartzScheduledFireEnumerator {

  public List<Instant> enumerate(
      Trigger trigger, Instant afterExclusive, Instant throughInclusive, int occurrenceLimit) {
    Objects.requireNonNull(trigger, "trigger");
    return enumerateFromSource(
        fireTimeSource(trigger), afterExclusive, throughInclusive, occurrenceLimit);
  }

  /** Quartz类型仅在适配器边界出现，应用规划器只消费项目时间源。 */
  public static ScheduledFireTimeSource fireTimeSource(Trigger trigger) {
    Objects.requireNonNull(trigger, "trigger");
    return afterExclusive -> {
      Date next = trigger.getFireTimeAfter(Date.from(afterExclusive));
      return EmptyChecks.isNull(next) ? null : next.toInstant();
    };
  }

  public List<Instant> enumerateFromSource(
      ScheduledFireTimeSource source,
      Instant afterExclusive,
      Instant throughInclusive,
      int occurrenceLimit) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(afterExclusive, "afterExclusive");
    Objects.requireNonNull(throughInclusive, "throughInclusive");
    if (occurrenceLimit <= 0) {
      throw new IllegalArgumentException("occurrenceLimit must be positive");
    }
    if (!afterExclusive.isBefore(throughInclusive)) {
      return List.of();
    }

    List<Instant> fireTimes = new ArrayList<>();
    Instant cursor = afterExclusive;
    Instant nextFireTime = source.nextAfter(cursor);
    while (EmptyChecks.isNotNull(nextFireTime) && !nextFireTime.isAfter(throughInclusive)) {
      if (fireTimes.size() == occurrenceLimit) {
        throw new OccurrenceLimitExceededException(occurrenceLimit);
      }
      Instant next = nextFireTime;
      if (!next.isAfter(cursor)) {
        throw new IllegalStateException("Quartz trigger returned a non-advancing fire time");
      }
      fireTimes.add(next);
      cursor = nextFireTime;
      nextFireTime = source.nextAfter(cursor);
    }
    return List.copyOf(fireTimes);
  }

  public static final class OccurrenceLimitExceededException extends IllegalStateException {

    public OccurrenceLimitExceededException(int occurrenceLimit) {
      super("Quartz schedule exceeds occurrence limit " + occurrenceLimit);
    }
  }
}
