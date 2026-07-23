package io.github.pinpols.batch.orchestrator.infrastructure.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.stateful.StatefulBackendGuard;
import io.github.pinpols.batch.orchestrator.config.QuotaProperties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class QuotaRuntimeBackendGuardTest {

  @Test
  void includesRedisLocationInGuardIdentity() {
    QuotaProperties properties = new QuotaProperties();
    properties.setRuntimeStore("redis");
    MockEnvironment environment =
        environment()
            .withProperty("spring.data.redis.host", "valkey")
            .withProperty("spring.data.redis.port", "6379")
            .withProperty("spring.data.redis.database", "2");

    StatefulBackendGuard.DesiredBackend desired = guard(properties, environment).desiredBackend();

    assertThat(desired.backend()).isEqualTo("redis");
    assertThat(desired.backendIdentity()).isEqualTo("host=valkey|port=6379|db=2");
  }

  @Test
  void includesPlatformJdbcLocationInGuardIdentity() {
    QuotaProperties properties = new QuotaProperties();
    properties.setRuntimeStore("database");

    StatefulBackendGuard.DesiredBackend desired = guard(properties, environment()).desiredBackend();

    assertThat(desired.backend()).isEqualTo("database");
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
    return new QuotaRuntimeBackendGuard(mock(DataSource.class), properties, environment);
  }

  private MockEnvironment environment() {
    return new MockEnvironment()
        .withProperty("spring.application.name", "batch-orchestrator")
        .withProperty("spring.datasource.url", "jdbc:postgresql://platform-db/batch_platform");
  }
}
