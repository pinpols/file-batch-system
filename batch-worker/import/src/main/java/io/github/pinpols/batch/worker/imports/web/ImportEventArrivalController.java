package io.github.pinpols.batch.worker.imports.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.worker.imports.config.ImportScannerProperties;
import io.github.pinpols.batch.worker.imports.runtime.ImportIngressScanner;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件驱动到达 inbound 通知控制器(路线图 4.1)。仅供内部对象存储事件源调用,不对外暴露。
 *
 * <p>对象落地时事件源 POST 一条通知,本端点即时触发一次 {@link ImportIngressScanner#scan()}。
 *
 * <p>到达发现延迟从「最长一个轮询周期(默认 30s)」降到接近实时。
 *
 * <p>扫描器只做「安全发现 + 登记」,不绕过 Trigger/Orchestrator 直接起任务,事件驱动只是「提前扫」。
 *
 * <p>开关默认关,关闭时只回 {@code triggered=false},等价历史纯轮询。
 *
 * <p>{@link AtomicBoolean} 在途守护让密集通知合并为「至多一次在途扫描」,避免事件风暴压垮扫描器。
 */
@Slf4j
@RestController
@RequestMapping("/internal/import/events")
@RequiredArgsConstructor
public class ImportEventArrivalController {

  private final ImportIngressScanner importIngressScanner;
  private final ImportScannerProperties scannerProperties;
  private final MeterRegistry meterRegistry;

  private final AtomicBoolean scanInFlight = new AtomicBoolean(false);

  @PostMapping("/object-arrival")
  public CommonResponse<ImportEventArrivalResponse> objectArrival(
      @RequestBody(required = false) ObjectArrivalNotification notification) {
    if (!scannerProperties.getEventArrival().isEnabled()) {
      return CommonResponse.success(
          new ImportEventArrivalResponse(false, "event-arrival-disabled"));
    }
    meterRegistry.counter("batch.import.event_arrival.notifications").increment();
    if (!scanInFlight.compareAndSet(false, true)) {
      // 已有扫描在途,合并本次通知(扫描器单次全量扫已覆盖此对象),避免事件风暴重复扫。
      return CommonResponse.success(
          new ImportEventArrivalResponse(false, "scan-already-in-flight"));
    }
    try {
      log.info(
          "Event-driven arrival notification received, triggering immediate ingress scan: "
              + "tenantId={} bucket={} objectKey={}",
          logSafe(notification == null ? null : notification.getTenantId()),
          logSafe(notification == null ? null : notification.getBucket()),
          logSafe(notification == null ? null : notification.getObjectKey()));
      importIngressScanner.scan();
      meterRegistry.counter("batch.import.event_arrival.scans").increment();
      return CommonResponse.success(new ImportEventArrivalResponse(true, null));
    } finally {
      scanInFlight.set(false);
    }
  }

  /**
   * 日志注入防护:用**白名单**只保留安全可打印字符,其余(含 CR/LF 及任意控制字符)替换为 '_'。
   *
   * <p>CodeQL 同时认可合规白名单及单独替换换行的常量模式(如共享 LogSanitizer 的 \R),并非仅白名单有效。
   * 此处保留原有的领域日志展示规则,继续过滤所有控制字符及白名单外字符;它不是对象 Key 的输入校验,
   * 不决定扫描器发现或接收哪些文件。
   */
  private static String logSafe(String value) {
    if (value == null) {
      return null;
    }
    return value.replaceAll("[^\\w.:/@=-]", "_");
  }
}
