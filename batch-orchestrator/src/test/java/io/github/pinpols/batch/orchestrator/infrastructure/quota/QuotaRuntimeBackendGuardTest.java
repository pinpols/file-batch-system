package io.github.pinpols.batch.orchestrator.infrastructure.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.config.ApplicationNameProvider;
import io.github.pinpols.batch.common.config.RuntimeInfrastructureInspector;
import io.github.pinpols.batch.common.stateful.StatefulBackendGuard;
import io.github.pinpols.batch.orchestrator.config.QuotaProperties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class QuotaRuntimeBackendGuardTest {

  @Test
  void includesRedisLocationInGuardIdentity() {
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
  void includesPlatformJdbcLocationInGuardIdentity() {
    QuotaProperties properties = new QuotaProperties();
    properties.setRuntimeStore(QuotaRuntimeBackends.DATABASE);

    StatefulBackendGuard.DesiredBackend desired =
        guard(properties, environment()).desiredBackend();

    assertThat(desired.backend()).isEqualTo(QuotaRuntimeBackends.DATABASE);
    assertThat(desired.backendIdentity())
        .isEqualTo("jdbc=jdbc:postgresql://platform-db/batch_platform");
  }

  @Test
  void rejectsUnknownRuntimeStoreInsteadOfSilentlySelectingNoImplementation() {
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
