package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ShedLock provider 指标:区分正常竞争与后端故障")
class MeteredLockProviderTest {

  private static final LockConfiguration LOCK_CONFIGURATION =
      new LockConfiguration(Instant.now(), "metrics-test", Duration.ofSeconds(10), Duration.ZERO);

  @Test
  @DisplayName("正常锁竞争返回空时不计为 provider 故障")
  void shouldNotCountNormalContentionAsFailure() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MeteredLockProvider provider =
        new MeteredLockProvider(configuration -> Optional.empty(), "redis", registry);

    assertThat(provider.lock(LOCK_CONFIGURATION)).isEmpty();
    assertThat(registry
            .get(MeteredLockProvider.ACQUIRE_FAILED)
            .tag("provider", "redis")
            .counter()
            .count())
        .isZero();
    assertThat(registry
            .get(MeteredLockProvider.PROVIDER_HEALTHY)
            .tag("provider", "redis")
            .gauge()
            .value())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("provider 异常时累计失败并把最近观测健康状态置为失败")
  void shouldRecordProviderFailure() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    LockProvider failing = configuration -> {
      throw new IllegalStateException("redis unavailable");
    };
    MeteredLockProvider provider = new MeteredLockProvider(failing, "redis", registry);

    assertThatThrownBy(() -> provider.lock(LOCK_CONFIGURATION))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("redis unavailable");
    assertThat(registry
            .get(MeteredLockProvider.ACQUIRE_FAILED)
            .tag("provider", "redis")
            .tag("reason", "redis_error")
            .counter()
            .count())
        .isEqualTo(1.0);
    assertThat(registry
            .get(MeteredLockProvider.PROVIDER_HEALTHY)
            .tag("provider", "redis")
            .gauge()
            .value())
        .isZero();
  }
}
