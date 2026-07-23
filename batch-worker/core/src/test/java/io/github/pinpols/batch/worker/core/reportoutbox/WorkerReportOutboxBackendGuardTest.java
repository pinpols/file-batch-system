package io.github.pinpols.batch.worker.core.reportoutbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.stateful.StatefulBackendGuard;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class WorkerReportOutboxBackendGuardTest {

  @Test
  void modelsDisabledAsAnExplicitBackendState() {
    WorkerReportOutboxProperties properties = new WorkerReportOutboxProperties();
    properties.setEnabled(false);
    properties.getBackendGuard().setCutoverId("disable-20260723-01");

    StatefulBackendGuard.DesiredBackend desired = guard(properties).desiredBackend();

    assertThat(desired.backend()).isEqualTo("disabled");
    assertThat(desired.backendIdentity()).isEqualTo("disabled");
    assertThat(desired.cutoverId()).isEqualTo("disable-20260723-01");
  }

  @Test
  void identifiesEnabledPlatformPgState() {
    WorkerReportOutboxProperties properties = new WorkerReportOutboxProperties();
    properties.setEnabled(true);
    properties.setStorage(WorkerReportOutboxStorage.PLATFORM_PG);

    StatefulBackendGuard.DesiredBackend desired = guard(properties).desiredBackend();

    assertThat(desired.backend()).isEqualTo("platform_pg");
    assertThat(desired.backendIdentity())
        .isEqualTo("jdbc=jdbc:postgresql://platform-db/batch_platform");
  }

  @Test
  void identifiesEnabledSqlitePath() {
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
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.application.name", "batch-worker-import")
            .withProperty("spring.datasource.url", "jdbc:postgresql://platform-db/batch_platform");
    return new WorkerReportOutboxBackendGuard(mock(DataSource.class), properties, environment);
  }
}
