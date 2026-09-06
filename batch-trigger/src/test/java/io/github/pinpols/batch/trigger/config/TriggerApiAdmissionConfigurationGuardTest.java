package io.github.pinpols.batch.trigger.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 单元测试：入口并发必须给 trigger 后台路径预留平台库连接。 */
class TriggerApiAdmissionConfigurationGuardTest {

  @Test
  void acceptsConcurrencyWithinPoolBudget() {
    TriggerRuntimeProperties properties = properties(8, 2, 2);

    assertThatCode(() -> guard(properties, 10).afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsConcurrencyAbovePoolBudget() {
    TriggerRuntimeProperties properties = properties(9, 2, 2);

    assertThatIllegalStateException()
        .isThrownBy(() -> guard(properties, 10).afterSingletonsInstantiated())
        .withMessageContaining("exceeds platform DB budget");
  }

  @Test
  void rejectsMinConcurrencyAboveMaxConcurrency() {
    TriggerRuntimeProperties properties = properties(4, 5, 2);

    assertThatIllegalStateException()
        .isThrownBy(() -> guard(properties, 10).afterSingletonsInstantiated())
        .withMessageContaining("must not exceed");
  }

  private static TriggerRuntimeProperties properties(int max, int min, int reserve) {
    TriggerRuntimeProperties properties = new TriggerRuntimeProperties();
    properties.setApiLaunchMaxConcurrency(max);
    properties.setApiLaunchMinConcurrency(min);
    properties.setApiLaunchDbReserveConnections(reserve);
    return properties;
  }

  private static TriggerApiAdmissionConfigurationGuard guard(
      TriggerRuntimeProperties properties, int poolSize) {
    HikariDataSource dataSource = Mockito.mock(HikariDataSource.class);
    when(dataSource.getMaximumPoolSize()).thenReturn(poolSize);
    return new TriggerApiAdmissionConfigurationGuard(properties, dataSource);
  }
}
