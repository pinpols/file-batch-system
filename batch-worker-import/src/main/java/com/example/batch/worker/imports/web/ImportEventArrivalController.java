package com.example.batch.worker.imports.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.worker.imports.config.ImportScannerProperties;
import com.example.batch.worker.imports.runtime.ImportIngressScanner;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
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
 * <p>{@link AtomicBoolean} 在途守护让密集通知合并为「至多一次在途扫描」,避免事件风暴打爆扫描器。
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
  public CommonResponse<Map<String, Object>> objectArrival(
      @RequestBody(required = false) ObjectArrivalNotification notification) {
    if (!scannerProperties.getEventArrival().isEnabled()) {
      return CommonResponse.success(Map.of("triggered", false, "reason", "event-arrival-disabled"));
    }
    meterRegistry.counter("batch.import.event_arrival.notifications").increment();
    if (!scanInFlight.compareAndSet(false, true)) {
      // 已有扫描在途,合并本次通知(扫描器单次全量扫已覆盖此对象),避免事件风暴重复扫。
      return CommonResponse.success(Map.of("triggered", false, "reason", "scan-already-in-flight"));
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
      return CommonResponse.success(Map.of("triggered", true));
    } finally {
      scanInFlight.set(false);
    }
  }

  /**
   * 日志注入防护:用**白名单**只保留安全可打印字符,其余(含 CR/LF 及任意控制字符)替换为 '_'。
   *
   * <p>原实现是删 CR/LF 黑名单——功能上够防 log forging,但 CodeQL 污点分析不把黑名单 replace 当 有效
   * sanitizer(java/log-injection 仍报 open)。改为正向白名单过滤后,CodeQL 识别为净化,
   * 同时语义更强(任何控制字符都挡)。tenantId/bucket/objectKey 合法值本就落在该白名单内。
   */
  private static String logSafe(String value) {
    if (value == null) {
      return null;
    }
    return value.replaceAll("[^\\w.:/@=-]", "_");
  }
}
