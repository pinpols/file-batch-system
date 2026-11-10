package io.github.pinpols.batch.worker.imports.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

@TestPropertySource(
    properties = {
      "batch.worker.report-outbox.enabled=true",
      "batch.worker.report-outbox.storage=SQLITE"
    })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("SQLite 回报发件箱与导入扫描事务隔离")
class ImportIngressSqliteTransactionIntegrationTest
    extends ImportIngressSpringTransactionIntegrationTest {
  @TempDir
  static Path temporaryDirectory;

  private final DataSource sqliteDataSource;
  private final PlatformTransactionManager sqliteManager;

  ImportIngressSqliteTransactionIntegrationTest(
      @Qualifier("importPlatformDataSource") DataSource platform,
      @Qualifier("importBusinessDataSource") DataSource business,
      @Qualifier("transactionManager") PlatformTransactionManager manager,
      ImportIngressScanRepository scans,
      PlatformFileRecordRepository files,
      PlatformFileAuditRepository audits,
      @Qualifier("workerReportOutboxDataSource") DataSource sqliteDataSource,
      @Qualifier("workerReportOutboxTransactionManager") PlatformTransactionManager sqliteManager) {
    super(platform, business, manager, scans, files, audits);
    this.sqliteDataSource = sqliteDataSource;
    this.sqliteManager = sqliteManager;
  }

  @DynamicPropertySource
  static void sqlitePath(DynamicPropertyRegistry registry) {
    registry.add(
        "batch.worker.report-outbox.sqlite-path",
        () -> temporaryDirectory.resolve("report-outbox.db").toString());
  }

  @Test
  @DisplayName("SQLite 发件箱事务仍使用其专属数据源")
  void reportOutbox_shouldKeepItsSqliteTransaction() {
    assertThat(sqliteManager).isInstanceOf(DataSourceTransactionManager.class);
    assertThat(((DataSourceTransactionManager) sqliteManager).getDataSource())
        .isSameAs(sqliteDataSource);
  }
}
