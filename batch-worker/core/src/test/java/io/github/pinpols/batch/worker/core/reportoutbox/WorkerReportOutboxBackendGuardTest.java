package io.github.pinpols.batch.worker.core.reportoutbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.config.ApplicationNameProvider;
import io.github.pinpols.batch.common.config.RuntimeInfrastructureInspector;
import io.github.pinpols.batch.common.stateful.StatefulBackendGuard;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

  private WorkerReportOutboxBackendGuard guard(WorkerReportOutboxProperties properties) {
    MockEnvironment environment = new MockEnvironment()
        .withProperty(ApplicationNameProvider.APPLICATION_NAME_KEY, "batch-worker-import")
        .withProperty(
            RuntimeInfrastructureInspector.DATASOURCE_URL_KEY,
            "jdbc:postgresql://platform-db/batch_platform");
    return new WorkerReportOutboxBackendGuard(
        mock(DataSource.class),
        properties,
        new ApplicationNameProvider(environment),
        new RuntimeInfrastructureInspector(environment));
  }
}
