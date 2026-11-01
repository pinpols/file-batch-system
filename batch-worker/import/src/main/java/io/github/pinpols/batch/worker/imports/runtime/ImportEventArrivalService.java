package io.github.pinpols.batch.worker.imports.runtime;

import io.github.pinpols.batch.worker.imports.config.ImportScannerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 对象到达时合并通知并提前触发既有的入口扫描。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportEventArrivalService {
  private final ImportIngressScanner scanner;
  private final ImportScannerProperties properties;
  private final MeterRegistry meterRegistry;
  private final AtomicBoolean scanInFlight = new AtomicBoolean(false);

  public Result onArrival(String tenantId, String bucket, String objectKey) {
    if (!properties.getEventArrival().isEnabled()) {
      return new Result(false, "event-arrival-disabled");
    }
    meterRegistry.counter("batch.import.event_arrival.notifications").increment();
    if (!properties.isEnabled()) {
      return new Result(false, "scanner-disabled");
    }
    if (!scanInFlight.compareAndSet(false, true)) {
      // 本轮快照未覆盖的对象由后续轮询发现。
      return new Result(false, "scan-already-in-flight");
    }
    try {
      log.info(
          "Event-driven arrival notification received, triggering immediate ingress scan: "
              + "tenantId={} bucket={} objectKey={}",
          logSafe(tenantId), logSafe(bucket), logSafe(objectKey));
      scanner.scan();
      meterRegistry.counter("batch.import.event_arrival.scans").increment();
      return new Result(true, null);
    } finally {
      scanInFlight.set(false);
    }
  }

  // 对日志展示使用白名单，不改变扫描器处理对象 key 的规则。
  private static String logSafe(String value) {
    return value == null ? null : value.replaceAll("[^\\w.:/@=-]", "_");
  }

  public record Result(boolean triggered, String reason) {}
}
