package io.github.pinpols.batch.orchestrator.infrastructure.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Low-cardinality view of the raw business-date catch-up backlog across enabled calendars. */
@Component
public class BatchDayCatchupMetrics {

  private static final String REMAINING_DAYS = "batch.batch_day.open.catchup.remaining_days";
  private static final String CALENDARS_BEHIND = "batch.batch_day.open.catchup.calendars_behind";

  private final MeterRegistry meterRegistry;
  private final AtomicLong remainingDays = new AtomicLong();
  private final AtomicLong calendarsBehind = new AtomicLong();
  private final AtomicLong pendingRemainingDays = new AtomicLong();
  private final AtomicLong pendingCalendarsBehind = new AtomicLong();

  public BatchDayCatchupMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    Gauge.builder(REMAINING_DAYS, remainingDays, AtomicLong::get)
        .description("Maximum raw business dates still to scan after the latest catch-up scan")
        .register(meterRegistry);
    Gauge.builder(CALENDARS_BEHIND, calendarsBehind, AtomicLong::get)
        .description("Enabled calendars with raw business dates still to scan")
        .register(meterRegistry);
    meterRegistry.counter("batch.batch_day.open.catchup.scan.failures");
  }

  public void beginScan() {
    pendingRemainingDays.set(0);
    pendingCalendarsBehind.set(0);
  }

  public void recordCalendarLag(long lagDays) {
    long normalized = Math.max(0, lagDays);
    pendingRemainingDays.accumulateAndGet(normalized, Math::max);
    if (normalized > 0) {
      pendingCalendarsBehind.incrementAndGet();
    }
  }

  public void completeScan() {
    remainingDays.set(pendingRemainingDays.get());
    calendarsBehind.set(pendingCalendarsBehind.get());
  }

  public void failScan() {
    meterRegistry.counter("batch.batch_day.open.catchup.scan.failures").increment();
  }
}
