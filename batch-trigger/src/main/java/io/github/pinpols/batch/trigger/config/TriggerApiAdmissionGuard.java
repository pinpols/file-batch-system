package io.github.pinpols.batch.trigger.config;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 手工 launch 的本地 admission 闸门。
 *
 * <p>Trigger 的异步写入仍必须先落库再返回；当应用线程在等待数据库连接或事务时，继续接收请求只会把
 * 排队压力转移到 Servlet 线程和连接池。该闸门在进入 launch 事务前快速拒绝超出本实例预算的请求，
 * 不参与 Quartz、outbox relay 或内部 scheduled launch，跨实例总量仍由租户 quota 负责。
 */
@Component
public class TriggerApiAdmissionGuard {

  private final TriggerRuntimeProperties properties;
  private final Object admissionLock = new Object();
  private final Counter rejectedCounter;
  private int activeRequests;
  private int adaptiveLimit;

  @Autowired
  public TriggerApiAdmissionGuard(
      TriggerRuntimeProperties properties, MeterRegistry meterRegistry) {
    this.properties = properties;
    this.adaptiveLimit = configuredMaxConcurrency();
    this.rejectedCounter = Counter.builder("batch.trigger.api_launch.admission.rejected")
        .description("Manual trigger launch requests rejected by the local admission guard")
        .register(meterRegistry);
    Gauge.builder(
            "batch.trigger.api_launch.admission.active", this, TriggerApiAdmissionGuard::active)
        .description("Manual trigger launch requests currently inside the local admission guard")
        .register(meterRegistry);
    Gauge.builder(
            "batch.trigger.api_launch.admission.limit",
            this,
            TriggerApiAdmissionGuard::effectiveConcurrencyForMetrics)
        .description(
            "Effective local trigger launch admission limit after optional AIMD adjustment")
        .register(meterRegistry);
  }

  /** 仅供不启动 Spring 容器的单元测试构造，生产始终使用带 MeterRegistry 的构造器。 */
  public TriggerApiAdmissionGuard(TriggerRuntimeProperties properties) {
    this.properties = properties;
    this.adaptiveLimit = configuredMaxConcurrency();
    this.rejectedCounter = null;
  }

  public <T> T execute(Supplier<T> action) {
    acquire();
    long startedNanos = System.nanoTime();
    try {
      return action.get();
    } finally {
      release(System.nanoTime() - startedNanos);
    }
  }

  private void acquire() {
    synchronized (admissionLock) {
      int limit = effectiveLimit();
      if (activeRequests >= limit) {
        if (rejectedCounter != null) {
          rejectedCounter.increment();
        }
        throw BizException.of(
            ResultCode.RATE_LIMITED,
            "error.common.rate_limited_detail",
            "trigger launch admission capacity reached");
      }
      activeRequests++;
    }
  }

  private void release(long elapsedNanos) {
    synchronized (admissionLock) {
      activeRequests--;
      adjustAdaptiveLimit(elapsedNanos);
    }
  }

  private int effectiveLimit() {
    int configuredMax = configuredMaxConcurrency();
    if (!properties.isApiLaunchAdaptiveEnabled()) {
      adaptiveLimit = configuredMax;
      return configuredMax;
    }
    int configuredMin =
        Math.min(configuredMax, Math.max(1, properties.getApiLaunchMinConcurrency()));
    adaptiveLimit = Math.max(configuredMin, Math.min(configuredMax, adaptiveLimit));
    return adaptiveLimit;
  }

  private void adjustAdaptiveLimit(long elapsedNanos) {
    if (!properties.isApiLaunchAdaptiveEnabled()) {
      return;
    }
    int configuredMax = configuredMaxConcurrency();
    int configuredMin =
        Math.min(configuredMax, Math.max(1, properties.getApiLaunchMinConcurrency()));
    long slowThresholdMillis = Math.max(1L, properties.getApiLaunchSlowRequestThresholdMillis());
    if (elapsedNanos >= TimeUnit.MILLISECONDS.toNanos(slowThresholdMillis)) {
      // AIMD:下游慢时快速减半，正常完成时每请求只恢复一个许可，避免在连接池饱和时来回抖动。
      adaptiveLimit = Math.max(configuredMin, Math.max(1, adaptiveLimit / 2));
    } else {
      adaptiveLimit = Math.min(configuredMax, adaptiveLimit + 1);
    }
  }

  private int configuredMaxConcurrency() {
    return Math.max(1, properties.getApiLaunchMaxConcurrency());
  }

  int effectiveConcurrencyForTest() {
    synchronized (admissionLock) {
      return effectiveLimit();
    }
  }

  private int active() {
    synchronized (admissionLock) {
      return activeRequests;
    }
  }

  private int effectiveConcurrencyForMetrics() {
    synchronized (admissionLock) {
      return effectiveLimit();
    }
  }
}
