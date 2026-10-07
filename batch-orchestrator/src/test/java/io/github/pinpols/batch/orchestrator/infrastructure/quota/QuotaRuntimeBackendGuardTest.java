package io.github.pinpols.batch.orchestrator.infrastructure.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.config.ApplicationNameProvider;
import io.github.pinpols.batch.common.config.RuntimeInfrastructureInspector;
import io.github.pinpols.batch.common.stateful.StatefulBackendGuard;
import io.github.pinpols.batch.orchestrator.config.QuotaProperties;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("配额运行态后端守卫,验证期望后端的标识解析与未知存储类型的快速失败")
class QuotaRuntimeBackendGuardTest {

  @Test
  @DisplayName("运行存储配置为 Redis 时,后端标识应包含主机,端口与库序号")
  void shouldIncludeRedisLocationInGuardIdentity_whenRuntimeStoreIsRedis() {
    QuotaProperties properties = new QuotaProperties();
    properties.setRuntimeStore(QuotaRuntimeBackends.REDIS);
    MockEnvironment environment = environment()
        .withProperty(RuntimeInfrastructureInspector.REDIS_HOST_KEY, "valkey")
        .withProperty(RuntimeInfrastructureInspector.REDIS_PORT_KEY, "6379")
        .withProperty(RuntimeInfrastructureInspector.REDIS_DATABASE_KEY, "2");

    StatefulBackendGuard.DesiredBackend desired = guard(properties, environment).desiredBackend();

    assertThat(desired.backend()).isEqualTo(QuotaRuntimeBackends.REDIS);
    assertThat(desired.backendIdentity()).isEqualTo("host=valkey|port=6379|db=2");
  }

  @Test
  @DisplayName("运行存储配置为数据库时,后端标识应包含平台数据库连接地址")
  void shouldIncludePlatformJdbcLocationInGuardIdentity_whenRuntimeStoreIsDatabase() {
    QuotaProperties properties = new QuotaProperties();
    properties.setRuntimeStore(QuotaRuntimeBackends.DATABASE);

    StatefulBackendGuard.DesiredBackend desired =
        guard(properties, environment()).desiredBackend();

    assertThat(desired.backend()).isEqualTo(QuotaRuntimeBackends.DATABASE);
    assertThat(desired.backendIdentity())
        .isEqualTo("jdbc=jdbc:postgresql://platform-db/batch_platform");
  }

  @Test
  @DisplayName("运行存储配置为无法识别的取值时应直接失败,不得静默退化为无实现")
  void shouldRejectUnknownRuntimeStore_whenNoImplementationMatches() {
    QuotaProperties properties = new QuotaProperties();
    properties.setRuntimeStore("redsi");

    assertThatThrownBy(() -> guard(properties, environment()).desiredBackend())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unsupported batch.quota.runtime-store");
  }

  private QuotaRuntimeBackendGuard guard(QuotaProperties properties, MockEnvironment environment) {
    return new QuotaRuntimeBackendGuard(
        mock(DataSource.class),
        properties,
        new ApplicationNameProvider(environment),
        new RuntimeInfrastructureInspector(environment));
  }

  private MockEnvironment environment() {
    return new MockEnvironment()
        .withProperty(ApplicationNameProvider.APPLICATION_NAME_KEY, "batch-orchestrator")
        .withProperty(
            RuntimeInfrastructureInspector.DATASOURCE_URL_KEY,
            "jdbc:postgresql://platform-db/batch_platform");
  }
}
