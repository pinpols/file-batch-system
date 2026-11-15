package io.github.pinpols.batch.trigger.config;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 手工 launch 的本地 admission 闸门。
 *
 * <p>Trigger 的异步写入仍必须先落库再返回；当应用线程在等待数据库连接或事务时，继续接收请求只会把
 * 排队压力转移到 Servlet 线程和连接池。该闸门在进入 launch 事务前快速拒绝超出本实例预算的请求，
 * 不参与 Quartz、outbox relay 或内部 scheduled launch，跨实例总量仍由租户 quota 负责。
 *
 * <p>排队按到达顺序发放许可。已有等待者时，新请求进入队尾，不能在许可释放时插到先到请求前面。
 * 并发上限、队列容量、等待超时和可选的 AIMD 收缩仍按配置生效。
 */
@Component
public class TriggerApiAdmissionGuard {

  private final TriggerRuntimeProperties properties;
  private final ReentrantLock admissionLock = new ReentrantLock(true);
  private final ArrayDeque<AdmissionWaiter> waitQueue = new ArrayDeque<>();
  private final Counter rejectedCounter;
  private final Counter queueFullRejectedCounter;
  private final Counter queueTimeoutRejectedCounter;
  private final Counter interruptedRejectedCounter;
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
    this.queueFullRejectedCounter = Counter.builder(
            "batch.trigger.api_launch.admission.queue_full_rejected")
        .description("Manual trigger launch requests rejected because the local queue is full")
        .register(meterRegistry);
    this.queueTimeoutRejectedCounter = Counter.builder(
            "batch.trigger.api_launch.admission.queue_timeout_rejected")
        .description("Manual trigger launch requests rejected after local queue wait expires")
        .register(meterRegistry);
    this.interruptedRejectedCounter = Counter.builder(
            "batch.trigger.api_launch.admission.interrupted_rejected")
        .description(
            "Manual trigger launch requests rejected after their waiting thread is interrupted")
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
    Gauge.builder(
            "batch.trigger.api_launch.admission.queued", this, TriggerApiAdmissionGuard::queued)
        .description("Manual trigger launch requests waiting for a local admission permit")
        .register(meterRegistry);
  }

  /** 仅供不启动 Spring 容器的单元测试构造，生产始终使用带 MeterRegistry 的构造器。 */
  public TriggerApiAdmissionGuard(TriggerRuntimeProperties properties) {
    this.properties = properties;
    this.adaptiveLimit = configuredMaxConcurrency();
    this.rejectedCounter = null;
    this.queueFullRejectedCounter = null;
    this.queueTimeoutRejectedCounter = null;
    this.interruptedRejectedCounter = null;
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
    admissionLock.lock();
    try {
      if (takePermitWhenIdle()) {
        return;
      }
      if (waitQueue.size() >= properties.getApiLaunchQueueCapacity()) {
        reject(RejectionReason.QUEUE_FULL);
      }
      waitForPermit();
    } finally {
      admissionLock.unlock();
    }
  }

  /**
   * 只有队列为空时才能直接占用许可。否则后到的请求会在先到请求被唤醒前抢走刚释放的名额。
   */
  private boolean takePermitWhenIdle() {
    if (activeRequests < effectiveLimit() && EmptyChecks.isEmpty(waitQueue)) {
      activeRequests++;
      return true;
    }
    return false;
  }

  private void waitForPermit() {
    AdmissionWaiter waiter = new AdmissionWaiter(admissionLock.newCondition());
    waitQueue.addLast(waiter);
    long remainingNanos = TimeUnit.MILLISECONDS.toNanos(properties.getApiLaunchQueueWaitMillis());
    try {
      while (!waiter.granted) {
        if (remainingNanos <= 0L) {
          reject(RejectionReason.QUEUE_TIMEOUT);
        }
        long startedNanos = System.nanoTime();
        try {
          waiter.condition.awaitNanos(remainingNanos);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          if (!waiter.granted) {
            reject(RejectionReason.INTERRUPTED);
          }
        }
        remainingNanos -= System.nanoTime() - startedNanos;
      }
    } finally {
      if (!waiter.granted) {
        waitQueue.remove(waiter);
      }
    }
  }

  private void release(long elapsedNanos) {
    admissionLock.lock();
    try {
      activeRequests--;
      adjustAdaptiveLimit(elapsedNanos);
      grantQueuedPermits();
    } finally {
      admissionLock.unlock();
    }
  }

  /** 按入队顺序把当前空闲许可发给队头，直到并发上限或队列耗尽。 */
  private void grantQueuedPermits() {
    while (activeRequests < effectiveLimit() && EmptyChecks.isNotEmpty(waitQueue)) {
      AdmissionWaiter waiter = waitQueue.removeFirst();
      waiter.granted = true;
      activeRequests++;
      waiter.condition.signal();
    }
  }

  private void reject(RejectionReason reason) {
    if (rejectedCounter != null) {
      rejectedCounter.increment();
      switch (reason) {
        case QUEUE_FULL -> queueFullRejectedCounter.increment();
        case QUEUE_TIMEOUT -> queueTimeoutRejectedCounter.increment();
        case INTERRUPTED -> interruptedRejectedCounter.increment();
      }
    }
    throw BizException.of(
        ResultCode.RATE_LIMITED,
        ResultCode.RATE_LIMITED.detailKey(),
        "trigger launch admission capacity reached");
  }

  private int effectiveLimit() {
    int configuredMax = configuredMaxConcurrency();
    if (!properties.isApiLaunchAdaptiveEnabled()) {
      adaptiveLimit = configuredMax;
      return configuredMax;
    }
    int requestedMinimum = properties.getApiLaunchMinConcurrency();
    int minimumConcurrency = Math.max(1, requestedMinimum);
    int configuredMin = Math.min(configuredMax, minimumConcurrency);
    int limitAtConfiguredMaximum = Math.min(configuredMax, adaptiveLimit);
    adaptiveLimit = Math.max(configuredMin, limitAtConfiguredMaximum);
    return adaptiveLimit;
  }

  private void adjustAdaptiveLimit(long elapsedNanos) {
    if (!properties.isApiLaunchAdaptiveEnabled()) {
      return;
    }
    int configuredMax = configuredMaxConcurrency();
    int requestedMinimum = properties.getApiLaunchMinConcurrency();
    int minimumConcurrency = Math.max(1, requestedMinimum);
    int configuredMin = Math.min(configuredMax, minimumConcurrency);
    long slowThresholdMillis = Math.max(1L, properties.getApiLaunchSlowRequestThresholdMillis());
    long slowThresholdNanos = TimeUnit.MILLISECONDS.toNanos(slowThresholdMillis);
    if (elapsedNanos >= slowThresholdNanos) {
      int halvedLimitAtLeastOne = Math.max(1, adaptiveLimit / 2);
      adaptiveLimit = Math.max(configuredMin, halvedLimitAtLeastOne);
    } else {
      adaptiveLimit = Math.min(configuredMax, adaptiveLimit + 1);
    }
  }

  private int configuredMaxConcurrency() {
    return Math.max(1, properties.getApiLaunchMaxConcurrency());
  }

  int effectiveConcurrencyForTest() {
    admissionLock.lock();
    try {
      return effectiveLimit();
    } finally {
      admissionLock.unlock();
    }
  }

  int queuedForTest() {
    admissionLock.lock();
    try {
      return waitQueue.size();
    } finally {
      admissionLock.unlock();
    }
  }

  private int active() {
    admissionLock.lock();
    try {
      return activeRequests;
    } finally {
      admissionLock.unlock();
    }
  }

  private int queued() {
    admissionLock.lock();
    try {
      return waitQueue.size();
    } finally {
      admissionLock.unlock();
    }
  }

  private int effectiveConcurrencyForMetrics() {
    admissionLock.lock();
    try {
      return effectiveLimit();
    } finally {
      admissionLock.unlock();
    }
  }

  private enum RejectionReason {
    QUEUE_FULL,
    QUEUE_TIMEOUT,
    INTERRUPTED
  }

  private static final class AdmissionWaiter {
    private final Condition condition;
    private boolean granted;

    private AdmissionWaiter(Condition condition) {
      this.condition = condition;
    }
  }
}
