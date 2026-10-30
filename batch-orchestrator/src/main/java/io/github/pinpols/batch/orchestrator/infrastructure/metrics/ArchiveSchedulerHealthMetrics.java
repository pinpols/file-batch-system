package io.github.pinpols.batch.orchestrator.infrastructure.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 暴露已启用归档调度最近一次正常返回的时间。 */
@Component
@RequiredArgsConstructor
public class ArchiveSchedulerHealthMetrics {

  private static final String LAST_SUCCESS_METRIC =
      "batch.archive.scheduler.last.success.timestamp.seconds";

  private final MeterRegistry meterRegistry;
  private final ConcurrentMap<String, AtomicLong> lastSuccessByScheduler =
      new ConcurrentHashMap<>();

  public void register(String scheduler) {
    lastSuccessByScheduler.computeIfAbsent(scheduler, name -> {
      AtomicLong lastSuccess = new AtomicLong(0);
      Gauge.builder(LAST_SUCCESS_METRIC, lastSuccess, AtomicLong::get)
          .tag("scheduler", name)
          .description("Unix timestamp of the last archive scheduler run that returned normally")
          .register(meterRegistry);
      return lastSuccess;
    });
  }

  public void markSuccess(String scheduler) {
    register(scheduler);
    AtomicLong lastSuccessEpochSeconds = lastSuccessByScheduler.get(scheduler);
    long currentEpochSecond = Instant.now().getEpochSecond();
    lastSuccessEpochSeconds.set(currentEpochSecond);
  }
}
