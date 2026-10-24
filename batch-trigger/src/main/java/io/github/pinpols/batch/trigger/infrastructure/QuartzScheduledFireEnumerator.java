package io.github.pinpols.batch.trigger.infrastructure;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import org.quartz.Trigger;
import org.springframework.stereotype.Component;

/** SKIP 是有效的日历结果。将其持久化为终态，避免当天一直保持 */
@Component
public class QuartzScheduledFireEnumerator {

  public List<Instant> enumerate(
      Trigger trigger, Instant afterExclusive, Instant throughInclusive, int occurrenceLimit) {
    Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(afterExclusive, "afterExclusive");
    Objects.requireNonNull(throughInclusive, "throughInclusive");
    if (occurrenceLimit <= 0) {
      throw new IllegalArgumentException("occurrenceLimit must be positive");
    }
    if (!afterExclusive.isBefore(throughInclusive)) {
      return List.of();
    }

    List<Instant> fireTimes = new ArrayList<>();
    Date cursor = Date.from(afterExclusive);
    Date nextFireTime = trigger.getFireTimeAfter(cursor);
    while (EmptyChecks.isNotNull(nextFireTime)
        && !nextFireTime.toInstant().isAfter(throughInclusive)) {
      if (fireTimes.size() == occurrenceLimit) {
        throw new OccurrenceLimitExceededException(occurrenceLimit);
      }
      Instant next = nextFireTime.toInstant();
      if (!next.isAfter(cursor.toInstant())) {
        throw new IllegalStateException("Quartz trigger returned a non-advancing fire time");
      }
      fireTimes.add(next);
      cursor = nextFireTime;
      nextFireTime = trigger.getFireTimeAfter(cursor);
    }
    return List.copyOf(fireTimes);
  }

  public static final class OccurrenceLimitExceededException extends IllegalStateException {

    public OccurrenceLimitExceededException(int occurrenceLimit) {
      super("Quartz schedule exceeds occurrence limit " + occurrenceLimit);
    }
  }
}
