package io.github.pinpols.batch.common.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

/**
 * 为 ShedLock provider 补充失败指标，但不改变锁竞争语义。
 *
 * <p>{@link LockProvider#lock(LockConfiguration)} 返回空表示同名锁正被其它实例持有，是正常竞争；只有底层 provider
 * 抛异常才计入失败。指标不携带锁名，避免定时任务名称进入高基数标签。
 */
public final class MeteredLockProvider implements LockProvider {

  static final String ACQUIRE_FAILED = "batch.shedlock.acquire.failed.total";
  static final String PROVIDER_HEALTHY = "batch.shedlock.provider.healthy";

  private final LockProvider delegate;
  private final String providerType;
  private final Counter acquireFailed;
  private final AtomicInteger healthy = new AtomicInteger(1);

  public MeteredLockProvider(
      LockProvider delegate, String providerType, MeterRegistry meterRegistry) {
    this.delegate = delegate;
    String normalizedProvider = providerType.toLowerCase(Locale.ROOT);
    this.providerType = normalizedProvider;
    this.acquireFailed = Counter.builder(ACQUIRE_FAILED)
        .tag("provider", normalizedProvider)
        .tag("reason", normalizedProvider + "_error")
        .description("ShedLock provider exceptions while acquiring a distributed lock")
        .register(meterRegistry);
    Gauge.builder(PROVIDER_HEALTHY, healthy, AtomicInteger::get)
        .tag("provider", normalizedProvider)
        .description("Last observed ShedLock provider call status: 1 healthy, 0 failed")
        .register(meterRegistry);
  }

  @Override
  public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
    try {
      Optional<SimpleLock> lock = delegate.lock(lockConfiguration);
      healthy.set(1);
      return lock;
    } catch (RuntimeException ex) {
      healthy.set(0);
      acquireFailed.increment();
      throw ex;
    }
  }

  /** 供诊断和装配测试确认当前后端，不向调用方暴露具体 provider 实现。 */
  public String providerType() {
    return providerType;
  }
}
