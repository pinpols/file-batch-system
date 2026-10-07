package io.github.pinpols.batch.trigger.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 单元测试：入口并发必须给 trigger 后台路径预留平台库连接。 */
@DisplayName("入口并发准入配置守卫:启动时校验 launch 并发不超连接池预算,且最小并发不大于最大并发")
class TriggerApiAdmissionConfigurationGuardTest {

  @Test
  @DisplayName("最大并发 8 加后台预留 2 落在连接池 10 内时,启动校验放行不抛异常")
  void acceptsConcurrencyWithinPoolBudget() {
    TriggerRuntimeProperties properties = properties(8, 2, 2);

    assertThatCode(() -> guard(properties, 10).afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("最大并发超过连接池扣除后台预留后的预算时,启动校验报超出平台库预算")
  void rejectsConcurrencyAbovePoolBudget() {
    TriggerRuntimeProperties properties = properties(9, 2, 2);

    assertThatIllegalStateException()
        .isThrownBy(() -> guard(properties, 10).afterSingletonsInstantiated())
        .withMessageContaining("exceeds platform DB budget");
  }

  @Test
  @DisplayName("最小并发大于最大并发时,启动校验报最小并发不得大于最大并发并阻止启动")
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
