package io.github.pinpols.batch.worker.core.reportoutbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.config.ApplicationNameProvider;
import io.github.pinpols.batch.common.config.RuntimeInfrastructureInspector;
import io.github.pinpols.batch.common.stateful.StatefulBackendGuard;
import java.nio.file.Path;
import java.util.Locale;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("上报发件箱后端守卫: 禁用态与启用态的后端身份解析")
class WorkerReportOutboxBackendGuardTest {

  @Test
  @DisplayName("上报发件箱关闭时, 期望后端显式标记为禁用态并保留切换标识")
  void shouldExposeDisabledState_whenOutboxDisabled() {
    WorkerReportOutboxProperties properties = new WorkerReportOutboxProperties();
    properties.setEnabled(false);
    properties.getBackendGuard().setCutoverId("disable-20260723-01");

    StatefulBackendGuard.DesiredBackend desired = guard(properties).desiredBackend();

    assertThat(desired.backend()).isEqualTo("disabled");
    assertThat(desired.backendIdentity()).isEqualTo("disabled");
    assertThat(desired.cutoverId()).isEqualTo("disable-20260723-01");
  }

  @Test
  @DisplayName("启用且使用平台库存储时, 期望后端标识为平台库并带上数据源地址")
  void shouldReportPlatformDatabaseIdentity_whenUsingPlatformStorage() {
    WorkerReportOutboxProperties properties = new WorkerReportOutboxProperties();
    properties.setEnabled(true);
    properties.setStorage(WorkerReportOutboxStorage.PLATFORM_PG);

    StatefulBackendGuard.DesiredBackend desired = guard(properties).desiredBackend();

    assertThat(desired.backend()).isEqualTo("platform_pg");
    assertThat(desired.backendIdentity())
        .isEqualTo("jdbc=jdbc:postgresql://platform-db/batch_platform");
  }

  @Test
  @DisplayName("启用且使用本地库存储时, 期望后端标识为本地库并归一化为绝对路径")
  void shouldReportLocalDatabaseIdentity_whenUsingLocalStorage() {
    WorkerReportOutboxProperties properties = new WorkerReportOutboxProperties();
    properties.setEnabled(true);
    properties.setStorage(WorkerReportOutboxStorage.SQLITE);
    properties.setSqlitePath("./target/outbox-switch-test.db");

    StatefulBackendGuard.DesiredBackend desired = guard(properties).desiredBackend();

    assertThat(desired.backend()).isEqualTo("sqlite");
    assertThat(desired.backendIdentity())
        .isEqualTo(
            "path=" + Path.of("./target/outbox-switch-test.db").toAbsolutePath().normalize());
  }

  @ParameterizedTest
  @CsvSource({
    "tr-TR,SQLITE,sqlite",
    "az-AZ,SQLITE,sqlite",
    "tr-TR,PLATFORM_PG,platform_pg",
    "az-AZ,PLATFORM_PG,platform_pg"
  })
  @ResourceLock("java.util.Locale.default")
  @DisplayName("主机语言改变时，存储后端身份仍与已登记协议值一致")
  void shouldKeepBackendIdentityStable_whenDefaultLocaleChanges(
      String language, WorkerReportOutboxStorage storage, String expectedBackend) {
    WorkerReportOutboxProperties properties = new WorkerReportOutboxProperties();
    properties.setEnabled(true);
    properties.setStorage(storage);
    properties.setSqlitePath("./target/outbox-switch-test.db");
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag(language));
      StatefulBackendGuard.DesiredBackend desired = guard(properties).desiredBackend();
      assertThat(desired.backend()).isEqualTo(expectedBackend);
    } finally {
      Locale.setDefault(previous);
    }
  }

  private WorkerReportOutboxBackendGuard guard(WorkerReportOutboxProperties properties) {
    MockEnvironment environment = new MockEnvironment()
        .withProperty(ApplicationNameProvider.APPLICATION_NAME_KEY, "batch-worker-import")
        .withProperty(
            RuntimeInfrastructureInspector.DATASOURCE_URL_KEY,
            "jdbc:postgresql://platform-db/batch_platform");
    @SuppressWarnings("unchecked")
    ObjectProvider<DataRedisConnectionDetails> redis = mock(ObjectProvider.class);
    RuntimeInfrastructureInspector inspector =
        new RuntimeInfrastructureInspector(environment, redis);
    return new WorkerReportOutboxBackendGuard(
        mock(DataSource.class), properties, new ApplicationNameProvider(environment), inspector);
  }
}
