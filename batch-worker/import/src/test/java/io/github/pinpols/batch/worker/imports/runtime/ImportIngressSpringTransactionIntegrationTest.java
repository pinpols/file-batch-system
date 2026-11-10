package io.github.pinpols.batch.worker.imports.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.core.infrastructure.FileAuditParam;
import io.github.pinpols.batch.worker.core.infrastructure.FileRecordParam;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.imports.BatchWorkerImportApplication;
import io.github.pinpols.batch.worker.imports.runtime.ImportIngressScanRepository.Lease;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(
    classes = BatchWorkerImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("导入扫描 Spring 平台库事务绑定")
class ImportIngressSpringTransactionIntegrationTest extends AbstractIntegrationTest {
  private final DataSource platform;
  private final DataSource business;
  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager manager;
  private final ImportIngressScanRepository scans;
  private final PlatformFileRecordRepository files;
  private final PlatformFileAuditRepository audits;

  ImportIngressSpringTransactionIntegrationTest(
      @Qualifier("importPlatformDataSource") DataSource platform,
      @Qualifier("importBusinessDataSource") DataSource business,
      @Qualifier("transactionManager") PlatformTransactionManager manager,
      ImportIngressScanRepository scans,
      PlatformFileRecordRepository files,
      PlatformFileAuditRepository audits) {
    this.platform = platform;
    this.business = business;
    this.jdbc = new JdbcTemplate(platform);
    this.manager = manager;
    this.scans = scans;
    this.files = files;
    this.audits = audits;
  }

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Test
  @DisplayName("双数据源上下文中扫描进度、文件登记及审计使用平台库同一事务")
  void scannerAndFileRepositories_shouldSharePlatformTransaction() {
    assertThat(platform).isNotSameAs(business);
    assertThat(jdbc.getDataSource()).isSameAs(platform);
    assertThat(manager).isInstanceOf(DataSourceTransactionManager.class);
    assertThat(((DataSourceTransactionManager) manager).getDataSource()).isSameAs(platform);

    String storageId = digestLikeId();
    String scope = digestLikeId();
    String digest = digestLikeId();
    String objectKey = "ingress/spring-transaction/" + UUID.randomUUID() + ".csv";
    Lease lease =
        scans.claim(storageId, scope, "test-bucket", "ingress/", digest).orElseThrow();
    AtomicReference<Long> insertedId = new AtomicReference<>();
    try {
      FileRecordParam file = FileRecordParam.builder()
          .tenantId("t1")
          .fileCategory("INPUT")
          .fileName("rollback.csv")
          .fileFormatType("DELIMITED")
          .storageType("S3")
          .storagePath(objectKey)
          .storageBucket("test-bucket")
          .sourceType("SYSTEM")
          .fileStatus("RECEIVED")
          .bizDate(LocalDate.parse("2026-11-09"))
          .build();
      assertThatThrownBy(() -> scans.fenced(lease, () -> {
            Long fileId = files.createFileRecord(file);
            insertedId.set(fileId);
            FileAuditParam audit = FileAuditParam.builder()
                .tenantId("t1")
                .fileId(fileId)
                .operationType("RECEIVE_SCAN")
                .operationResult("SUCCESS")
                .build();
            audits.appendAudit(audit);
            scans.advance(lease, "INDEX", null, 0, lease.cycle());
            throw new IllegalStateException("验证 Spring 平台事务回滚");
          }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("验证 Spring 平台事务回滚");

      assertThat(files.existsFileRecordByStoragePath("t1", "test-bucket", objectKey))
          .isFalse();
      String auditSql = "SELECT count(*) FROM batch.file_audit_log WHERE file_id = ?";
      Long auditCount = jdbc.queryForObject(auditSql, Long.class, insertedId.get());
      assertThat(auditCount).isZero();
      String phaseSql = "SELECT phase FROM batch.import_ingress_scan_state WHERE scope_id = ?";
      String phase = jdbc.queryForObject(phaseSql, String.class, scope);
      assertThat(phase).isEqualTo("DISCOVER");
    } finally {
      scans.release(lease);
    }
  }

  private static String digestLikeId() {
    return UUID.randomUUID().toString().replace("-", "") + "0".repeat(32);
  }
}
