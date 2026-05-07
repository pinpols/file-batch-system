package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.config.BatchClockConfig;
import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.config.FileGovernanceProperties;
import com.example.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import com.example.batch.orchestrator.infrastructure.file.FileGovernanceScheduler;
import com.example.batch.orchestrator.infrastructure.file.MinioGovernanceStorage;
import com.example.batch.orchestrator.infrastructure.redis.FileGovernanceMetricsCacheService;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import com.example.batch.testing.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootTest(
    classes = FileGovernanceIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "batch.file-governance.latency.enabled=true",
      "batch.file-governance.archive.enabled=true",
      "batch.file-governance.reconcile.enabled=true",
      "batch.file-governance.arrival.enabled=true",
      "batch.file-governance.reconcile.default-tenant-id=default-tenant",
      "batch.file-governance.reconcile.prefix=incoming/",
      "batch.file-governance.latency.arrival-delay-threshold-seconds=600",
      "batch.file-governance.latency.processing-delay-threshold-seconds=900",
      "batch.file-governance.archive.retention-days=7",
      "batch.file-governance.archive.cleanup-batch-size=10",
      "batch.file-governance.arrival.batch-size=10",
      "batch.file-governance.reconcile.batch-size=10",
      "batch.startup-self-check.enabled=false"
    })
class FileGovernanceIntegrationTest extends AbstractIntegrationTest {

  private static final class FileRecordSpec {
    private final String tenantId;
    private final String fileName;
    private final String fileCategory;
    private final String fileStatus;
    private final String storageType;
    private String storageBucket;
    private final String storagePath;
    private final String metadataJson;
    private Instant createdAt = BatchDateTimeSupport.utcNow();
    private Instant updatedAt = BatchDateTimeSupport.utcNow();

    private FileRecordSpec(
        String tenantId,
        String fileName,
        String fileCategory,
        String fileStatus,
        String storageType,
        String storagePath,
        String metadataJson) {
      this.tenantId = tenantId;
      this.fileName = fileName;
      this.fileCategory = fileCategory;
      this.fileStatus = fileStatus;
      this.storageType = storageType;
      this.storagePath = storagePath;
      this.metadataJson = metadataJson;
    }

    private FileRecordSpec storageBucket(String storageBucket) {
      this.storageBucket = storageBucket;
      return this;
    }

    private FileRecordSpec createdAt(Instant createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    private FileRecordSpec updatedAt(Instant updatedAt) {
      this.updatedAt = updatedAt;
      return this;
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @ImportAutoConfiguration(RestClientAutoConfiguration.class)
  @EnableScheduling
  @EnableConfigurationProperties({FileGovernanceProperties.class, MinioStorageProperties.class})
  @MapperScan("com.example.batch.orchestrator.mapper")
  @Import({
    BatchClockConfig.class,
    FileGovernanceScheduler.class,
    FileGovernanceRepository.class,
    MinioGovernanceStorage.class,
    OrchestratorRedisSupport.class,
    FileGovernanceMetricsCacheService.class
  })
  static class TestApplication {

    @Bean
    MinioClient minioClient(MinioStorageProperties props) {
      return MinioClient.builder()
          .endpoint(props.getEndpoint())
          .credentials(props.getAccessKey(), props.getSecretKey())
          .build();
    }
  }

  private static final String TENANT_ID = "t1";

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private FileGovernanceScheduler fileGovernanceScheduler;

  @Autowired private FileGovernanceRepository fileGovernanceRepository;

  @Autowired private MeterRegistry meterRegistry;

  @Test
  void shouldCollectLatencyMetricsForDelayedArrivalFiles() {
    // collectLatencyMetrics() scopes queries to reconcile.default-tenant-id (see
    // FileGovernanceScheduler).
    String latencyTenantId = "default-tenant";
    Instant now = BatchDateTimeSupport.utcNow();
    String suffix = suffix();
    insertFileRecord(
        new FileRecordSpec(
                latencyTenantId,
                "delay-file-" + suffix + ".csv",
                "INPUT",
                "RECEIVED",
                "S3",
                "incoming/delay-file-" + suffix + ".csv",
                "{\"expectedArrivalTime\":\"" + now.minusSeconds(7200) + "\"}")
            .storageBucket(minioBucket())
            .createdAt(now)
            .updatedAt(now));

    fileGovernanceScheduler.collectLatencyMetrics();

    assertThat(meterRegistry.get("batch.file.arrival.delay.violations").gauge().value())
        .isEqualTo(1.0d);
    assertThat(meterRegistry.get("batch.file.arrival.delay.max.seconds").gauge().value())
        .isGreaterThanOrEqualTo(7200.0d);
  }

  @Test
  void shouldArchiveExpiredFilesAndWriteCleanupAudit() throws Exception {
    ensureMinioBucket(minioBucket());
    String objectName = "archive/cleanup/" + suffix() + ".csv";
    putObject(objectName, "one,two,three\n");

    Long fileId =
        insertFileRecord(
            new FileRecordSpec(
                    TENANT_ID,
                    "archive-file-" + suffix() + ".csv",
                    "INPUT",
                    "ARCHIVED",
                    "S3",
                    objectName,
                    "{}")
                .storageBucket(minioBucket())
                .createdAt(BatchDateTimeSupport.utcNow().minusSeconds(9L * 24L * 3600L))
                .updatedAt(BatchDateTimeSupport.utcNow().minusSeconds(9L * 24L * 3600L)));

    fileGovernanceScheduler.cleanupArchivedFiles();

    Map<String, Object> fileRecord =
        jdbcTemplate.queryForMap(
            "select file_status, metadata_json->>'cleanupReason' as cleanup_reason from"
                + " batch.file_record where id = ?",
            fileId);
    assertThat(fileRecord.get("file_status")).isEqualTo("DELETED");
    assertThat(fileRecord.get("cleanup_reason")).isEqualTo("ARCHIVE_RETENTION_EXPIRED");

    Integer auditCount =
        jdbcTemplate.queryForObject(
            """
            select count(1)::int
            from batch.file_audit_log
            where file_id = ? and operation_type = 'CLEANUP' and operation_result = 'SUCCESS'
            """,
            Integer.class,
            fileId);
    assertThat(auditCount).isEqualTo(1);

    MinioClient client = minioClient();
    assertThatThrownBy(
            () ->
                client.statObject(
                    StatObjectArgs.builder().bucket(minioBucket()).object(objectName).build()))
        .isInstanceOf(Exception.class);
  }

  @Test
  void shouldReconcileOrphanObjectIntoFileRecord() throws Exception {
    String objectName = "incoming/" + suffix() + "-orphan.csv";
    putObject(objectName, "alpha,beta\n1,2\n");

    fileGovernanceScheduler.reconcileObjectStorage();

    Map<String, Object> reconciled =
        jdbcTemplate.queryForMap(
            """
            select file_name,
                   file_status,
                   metadata_json->>'reconciled' as reconciled_flag,
                   storage_path
            from batch.file_record
            where storage_path = ?
            """,
            objectName);
    assertThat(reconciled.get("file_name"))
        .isEqualTo(objectName.substring(objectName.lastIndexOf('/') + 1));
    assertThat(reconciled.get("file_status")).isEqualTo("RECEIVED");
    assertThat(reconciled.get("reconciled_flag")).isEqualTo("true");
    assertThat(reconciled.get("storage_path")).isEqualTo(objectName);

    Integer auditCount =
        jdbcTemplate.queryForObject(
            """
            select count(1)::int
            from batch.file_audit_log
            where operation_type = 'RECONCILE_REGISTER'
              and trace_id like 'reconcile-%'
            """,
            Integer.class);
    assertThat(auditCount).isGreaterThanOrEqualTo(1);
  }

  @Test
  void shouldTriggerArrivalGroupWhenAllFilesArrive() {
    String groupCode = "arrival-group-" + suffix();
    String requiredSet = "file-a.csv,file-b.csv";
    String metadata =
        """
        {
          "fileGroupCode": "%s",
          "waitFileGroupMode": "ALL_OF",
          "requiredFileSet": "%s",
          "arrivalTimeoutAction": "MANUAL_CONFIRM",
          "triggerOnComplete": true,
          "latestTolerableTime": "%s"
        }
        """
            .formatted(groupCode, requiredSet, BatchDateTimeSupport.utcNow().plusSeconds(3600));

    insertFileRecord(
        new FileRecordSpec(
                TENANT_ID,
                "file-a.csv",
                "INPUT",
                "RECEIVED",
                "LOCAL",
                "incoming/" + groupCode + "/file-a.csv",
                metadata)
            .createdAt(BatchDateTimeSupport.utcNow())
            .updatedAt(BatchDateTimeSupport.utcNow()));
    insertFileRecord(
        new FileRecordSpec(
                TENANT_ID,
                "file-b.csv",
                "INPUT",
                "RECEIVED",
                "LOCAL",
                "incoming/" + groupCode + "/file-b.csv",
                metadata)
            .createdAt(BatchDateTimeSupport.utcNow())
            .updatedAt(BatchDateTimeSupport.utcNow()));

    fileGovernanceScheduler.manageFileArrivalGroups();

    var rows =
        jdbcTemplate.queryForList(
            """
            select file_name,
                   metadata_json->>'arrivalState' as arrival_state,
                   metadata_json->>'arrivalReason' as arrival_reason
            from batch.file_record
            where tenant_id = ? and metadata_json->>'fileGroupCode' = ?
            order by file_name
            """,
            TENANT_ID,
            groupCode);
    assertThat(rows).hasSize(2);
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row.get("arrival_state")).isEqualTo("TRIGGERED");
              assertThat(row.get("arrival_reason")).isEqualTo("ALL_FILES_ARRIVED");
            });

    var summaries =
        fileGovernanceRepository.selectArrivalGroupSummaries(TENANT_ID, groupCode, "TRIGGERED");
    assertThat(summaries).hasSize(1);
    assertThat(((Number) summaries.get(0).get("triggered_count")).longValue()).isEqualTo(2L);
  }

  private Long insertFileRecord(FileRecordSpec spec) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.file_record (
            tenant_id, file_category, file_name, original_file_name, file_ext, file_format_type,
            charset, mime_type, file_size_bytes, checksum_type, checksum_value,
            storage_type, storage_path, storage_bucket, file_version, file_generation_no,
            is_latest, source_type, source_ref, file_status, biz_date, trace_id,
            metadata_json, created_at, updated_at
        ) values (
            ?, ?, ?, ?, 'csv', 'DELIMITED',
            'UTF-8', 'text/csv', 128, 'NONE', null,
            ?, ?, ?, 'v1', 1,
            true, 'SYSTEM', 'file-governance-it', ?, ?, ?,
            ?::jsonb, ?, ?
        ) returning id
        """,
        Long.class,
        spec.tenantId,
        spec.fileCategory,
        spec.fileName,
        spec.fileName,
        spec.storageType,
        spec.storagePath,
        spec.storageBucket,
        spec.fileStatus,
        Date.valueOf(LocalDate.of(2026, 3, 27)),
        "trace-" + suffix(),
        spec.metadataJson,
        Timestamp.from(spec.createdAt),
        Timestamp.from(spec.updatedAt));
  }

  private void putObject(String objectName, String content) throws Exception {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    minioClient()
        .putObject(
            PutObjectArgs.builder().bucket(minioBucket()).object(objectName).stream(
                    new ByteArrayInputStream(bytes), bytes.length, 5 * 1024 * 1024)
                .contentType("text/plain")
                .build());
  }

  private MinioClient minioClient() {
    return MinioClient.builder()
        .endpoint(minioEndpoint())
        .credentials("minioadmin", "minioadmin123")
        .build();
  }

  private String suffix() {
    return Long.toUnsignedString(System.nanoTime());
  }
}
