package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import io.github.pinpols.batch.common.config.BatchClockConfig;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.enums.FileIntegrityState;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.config.FileGovernanceProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.file.BundleArrivalLauncher;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceScheduler;
import io.github.pinpols.batch.orchestrator.infrastructure.file.S3GovernanceStorage;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.FileGovernanceMetricsCacheService;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mybatis.spring.annotation.MapperScan;
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
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

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
@EnabledIf("s3BackendActive")
@DisplayName("文件治理调度在延迟统计,归档清理,对象对账与到达组触发上的验收")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
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
  @EnableConfigurationProperties({FileGovernanceProperties.class, S3StorageProperties.class})
  @MapperScan("io.github.pinpols.batch.orchestrator.mapper")
  @Import({
    BatchClockConfig.class,
    FileGovernanceScheduler.class,
    // ADR-046 2c-2b:FileGovernanceScheduler 现依赖 BundleArrivalLauncher,须一并导入。
    // 本测的到达组无 bundleJobCode → launchIfBundle 早返回,不触 LaunchService(故无需 mock)。
    BundleArrivalLauncher.class,
    FileGovernanceRepository.class,
    S3GovernanceStorage.class,
    OrchestratorRedisSupport.class,
    FileGovernanceMetricsCacheService.class
  })
  static class TestApplication {

    @Bean
    S3Client s3Client(S3StorageProperties props) {
      return S3Client.builder()
          .endpointOverride(URI.create(props.getEndpoint()))
          .credentialsProvider(StaticCredentialsProvider.create(
              AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
          .forcePathStyle(true)
          .region(Region.US_EAST_1)
          .build();
    }
  }

  private static final String TENANT_ID = "t1";

  private final JdbcTemplate jdbcTemplate;
  private final FileGovernanceScheduler fileGovernanceScheduler;
  private final FileGovernanceRepository fileGovernanceRepository;
  private final MeterRegistry meterRegistry;
  private final FileGovernanceProperties fileGovernanceProperties;

  @MockitoSpyBean
  private FileGovernanceRepository fileGovernanceRepositorySpy;

  FileGovernanceIntegrationTest(
      JdbcTemplate jdbcTemplate,
      FileGovernanceScheduler fileGovernanceScheduler,
      FileGovernanceRepository fileGovernanceRepository,
      MeterRegistry meterRegistry,
      FileGovernanceProperties fileGovernanceProperties) {
    this.jdbcTemplate = jdbcTemplate;
    this.fileGovernanceScheduler = fileGovernanceScheduler;
    this.fileGovernanceRepository = fileGovernanceRepository;
    this.meterRegistry = meterRegistry;
    this.fileGovernanceProperties = fileGovernanceProperties;
  }

  @Test
  @DisplayName("真实数据库下开启完整性要求后只有 VERIFIED 文件能够触发")
  void shouldTriggerVerifiedGroup_whenDatabaseReturnsVerifiedIntegrityState() {
    final String updateFileRecordSql = """
        update batch.file_record set checksum_type = 'SHA-256', checksum_value = ? where tenant_id = ?\s\
        and id = ?
    """;
    final String selectMetadataJsonSql = """
        select metadata_json->>'arrivalState' from batch.file_record where tenant_id = ? and id = ?
    """;

    String groupCode = "verified-group-" + suffix();
    String metadata = """
        {"fileGroupCode":"%s","requiredFileSet":"verified.csv","triggerOnComplete":true,"integrityState":"%s"}
        """.formatted(groupCode, FileIntegrityState.VERIFIED.code());
    Long fileId = insertFileRecord(new FileRecordSpec(
        TENANT_ID,
        "verified.csv",
        "INPUT",
        FileStatus.RECEIVED.code(),
        "LOCAL",
        "incoming/" + groupCode + "/verified.csv",
        metadata));
    jdbcTemplate.update(
        updateFileRecordSql,
        "a".repeat(64),
        TENANT_ID,
        fileId);
    boolean original = fileGovernanceProperties.getArrival().isRequireVerified();
    try {
      fileGovernanceProperties.getArrival().setRequireVerified(true);
      fileGovernanceScheduler.manageFileArrivalGroups();
      String selectedMetadataJson =
          jdbcTemplate.queryForObject(selectMetadataJsonSql, String.class, TENANT_ID, fileId);
      assertThat(selectedMetadataJson).isEqualTo("TRIGGERED");
    } finally {
      fileGovernanceProperties.getArrival().setRequireVerified(original);
    }
  }

  @Test
  @DisplayName("真实数据库下 checksum 字段存在但没有 VERIFIED 背书时保持等待")
  void shouldWaitForVerifiedGroup_whenOnlyChecksumColumnsArePresent() {
  final String updateFileRecordSql = """
      update batch.file_record set checksum_type = 'SHA-256', checksum_value = ? where tenant_id = ? and id = ?
  """;
  final String selectMetadataJsonSql = """
      select metadata_json->>'arrivalState' from batch.file_record where tenant_id = ? and id = ?
  """;
  final String selectMetadataJsonSql2 = """
      select metadata_json->>'arrivalReason' from batch.file_record where tenant_id = ? and id = ?
  """;

    String groupCode = "unverified-group-" + suffix();
    String metadata = """
        {"fileGroupCode":"%s","requiredFileSet":"unverified.csv","triggerOnComplete":true}
        """.formatted(groupCode);
    Long fileId = insertFileRecord(new FileRecordSpec(
        TENANT_ID,
        "unverified.csv",
        "INPUT",
        FileStatus.RECEIVED.code(),
        "LOCAL",
        "incoming/" + groupCode + "/unverified.csv",
        metadata));
    jdbcTemplate.update(
        updateFileRecordSql,
        "a".repeat(64),
        TENANT_ID,
        fileId);
    boolean original = fileGovernanceProperties.getArrival().isRequireVerified();
    try {
      fileGovernanceProperties.getArrival().setRequireVerified(true);
      fileGovernanceScheduler.manageFileArrivalGroups();
      String selectedMetadataJson =
          jdbcTemplate.queryForObject(selectMetadataJsonSql, String.class, TENANT_ID, fileId);
      assertThat(selectedMetadataJson).isEqualTo("WAITING_ARRIVAL");
      String selectedMetadataJsonRead2 =
          jdbcTemplate.queryForObject(selectMetadataJsonSql2, String.class, TENANT_ID, fileId);
      assertThat(selectedMetadataJsonRead2).isEqualTo("ARRIVED_PENDING_VERIFY");
    } finally {
      fileGovernanceProperties.getArrival().setRequireVerified(original);
    }
  }

  @Test
  @DisplayName("延迟到达的文件计入送达延迟违规计数,最大延迟秒数不低于实际延迟")
  void shouldCollectLatencyMetricsForDelayedArrivalFiles() {
    // collectLatencyMetrics() scopes queries to reconcile.default-tenant-id (see
    // FileGovernanceScheduler).
    String latencyTenantId = "default-tenant";
    Instant now = BatchDateTimeSupport.utcNow();
    String suffix = suffix();
    insertFileRecord(new FileRecordSpec(
            latencyTenantId,
            "delay-file-" + suffix + ".csv",
            "INPUT",
            FileStatus.RECEIVED.code(),
            "S3",
            "incoming/delay-file-" + suffix + ".csv",
            "{\"expectedArrivalTime\":\"" + now.minusSeconds(7200) + "\"}")
        .storageBucket(s3Bucket())
        .createdAt(now)
        .updatedAt(now));

    fileGovernanceScheduler.collectLatencyMetrics();

    assertThat(meterRegistry.get("batch.file.arrival.delay.violations").gauge().value())
        .isEqualTo(1.0d);
    assertThat(meterRegistry.get("batch.file.arrival.delay.max.seconds").gauge().value())
        .isGreaterThanOrEqualTo(7200.0d);
  }

  @Test
  @DisplayName("超过保留期的归档文件被清理为已删除状态,写入清理成功审计并移除对象存储中的对象")
  void shouldArchiveExpiredFilesAndWriteCleanupAudit() throws Exception {
    final String selectFileStatusSql = """
        select file_status, metadata_json->>'cleanupReason' as cleanup_reason from batch.file_record\s\
        where id = ?\
        """;
    final String selectCountSql = """
              select count(1)::int
              from batch.file_audit_log
              where file_id = ? and operation_type = 'CLEANUP' and operation_result = 'SUCCESS'
              """;

    ensureS3Bucket(s3Bucket());
    String objectName = "archive/cleanup/" + suffix() + ".csv";
    putObject(objectName, "one,two,three\n");

    Long fileId = insertFileRecord(new FileRecordSpec(
            TENANT_ID,
            "archive-file-" + suffix() + ".csv",
            "INPUT",
            FileStatus.ARCHIVED.code(),
            "S3",
            objectName,
            "{}")
        .storageBucket(s3Bucket())
        .createdAt(BatchDateTimeSupport.utcNow().minusSeconds(9L * 24L * 3600L))
        .updatedAt(BatchDateTimeSupport.utcNow().minusSeconds(9L * 24L * 3600L)));

    fileGovernanceScheduler.cleanupArchivedFiles();

    Map<String, Object> fileRecord = jdbcTemplate.queryForMap(
        selectFileStatusSql,
        fileId);
    assertThat(fileRecord)
        .containsEntry("file_status", FileStatus.DELETED.code())
        .containsEntry("cleanup_reason", "ARCHIVE_RETENTION_EXPIRED");

    Integer auditCount = jdbcTemplate.queryForObject(selectCountSql, Integer.class, fileId);
    assertThat(auditCount).isEqualTo(1);

    S3Client client = s3Client();
    assertThatThrownBy(() -> client.headObject(
            HeadObjectRequest.builder().bucket(s3Bucket()).key(objectName).build()))
        .isInstanceOf(Exception.class);
  }

  @Test
  @DisplayName("对象存储中的孤儿对象被登记为已接收文件记录,并写入对账审计")
  void shouldReconcileOrphanObjectIntoFileRecord() throws Exception {
    final String selectFileNameSql = """
              select file_name,
                     file_status,
                     metadata_json->>'reconciled' as reconciled_flag,
                     storage_path
              from batch.file_record
              where storage_path = ?
              """;
    final String selectCountSql = """
              select count(1)::int
              from batch.file_audit_log
              where operation_type = 'RECONCILE_REGISTER'
                and trace_id like 'reconcile-%'
              """;

    String reconcilePrefix = "incoming/reconcile-it-" + suffix() + "/";
    String objectName = reconcilePrefix + "orphan.csv";
    putObject(objectName, "alpha,beta\n1,2\n");

    String originalPrefix = fileGovernanceProperties.getReconcile().getPrefix();
    try {
      fileGovernanceProperties.getReconcile().setPrefix(reconcilePrefix);
      fileGovernanceScheduler.reconcileObjectStorage();
    } finally {
      fileGovernanceProperties.getReconcile().setPrefix(originalPrefix);
    }

    Map<String, Object> reconciled = jdbcTemplate.queryForMap(selectFileNameSql, objectName);
    assertThat(reconciled)
        .containsEntry("file_name", objectName.substring(objectName.lastIndexOf('/') + 1));
    assertThat(reconciled)
        .containsEntry("file_status", FileStatus.RECEIVED.code())
        .containsEntry("reconciled_flag", "true")
        .containsEntry("storage_path", objectName);

    Integer auditCount = jdbcTemplate.queryForObject(selectCountSql, Integer.class);
    assertThat(auditCount).isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName("到达组内文件全部到位后触发组,记录触发状态与原因并汇总触发数量")
  void shouldTriggerArrivalGroupWhenAllFilesArrive() {
    final String selectFileNameSql = """
              select file_name,
                     metadata_json->>'arrivalState' as arrival_state,
                     metadata_json->>'arrivalReason' as arrival_reason
              from batch.file_record
              where tenant_id = ? and metadata_json->>'fileGroupCode' = ?
              order by file_name
              """;

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
        """.formatted(groupCode, requiredSet, BatchDateTimeSupport.utcNow().plusSeconds(3600));

    insertFileRecord(new FileRecordSpec(
            TENANT_ID,
            "file-a.csv",
            "INPUT",
            FileStatus.RECEIVED.code(),
            "LOCAL",
            "incoming/" + groupCode + "/file-a.csv",
            metadata)
        .createdAt(BatchDateTimeSupport.utcNow())
        .updatedAt(BatchDateTimeSupport.utcNow()));
    insertFileRecord(new FileRecordSpec(
            TENANT_ID,
            "file-b.csv",
            "INPUT",
            FileStatus.RECEIVED.code(),
            "LOCAL",
            "incoming/" + groupCode + "/file-b.csv",
            metadata)
        .createdAt(BatchDateTimeSupport.utcNow())
        .updatedAt(BatchDateTimeSupport.utcNow()));

    fileGovernanceScheduler.manageFileArrivalGroups();

    var rows = jdbcTemplate.queryForList(selectFileNameSql, TENANT_ID, groupCode);
    assertThat(rows).hasSize(2);
    assertThat(rows).allSatisfy(row -> {
      assertThat(row)
          .containsEntry("arrival_state", "TRIGGERED")
          .containsEntry("arrival_reason", "ALL_FILES_ARRIVED");
    });

    var summaries =
        fileGovernanceRepository.selectArrivalGroupSummaries(TENANT_ID, groupCode, "TRIGGERED");
    assertThat(summaries).hasSize(1);
    assertThat(summaries.get(0).triggeredCount()).isEqualTo(2L);
  }

  @Test
  @DisplayName("未收到到达通知时,下一轮持久化轮询仍能发现迟到文件并触发完整文件组")
  void shouldRecoverLateFileArrivalByPollingDurableFileRecords() {
    final String selectMetadataJsonSql = """
        select metadata_json->>'arrivalState' as arrival_state from batch.file_record where tenant_id = ?\s\
        and metadata_json->>'fileGroupCode' = ?\
        """;

    String groupCode = "arrival-recovery-" + suffix();
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
        """.formatted(groupCode, requiredSet, BatchDateTimeSupport.utcNow().plusSeconds(3600));

    insertFileRecord(new FileRecordSpec(
            TENANT_ID,
            "file-b.csv",
            "INPUT",
            FileStatus.RECEIVED.code(),
            "LOCAL",
            "incoming/" + groupCode + "/file-b.csv",
            metadata)
        .createdAt(BatchDateTimeSupport.utcNow())
        .updatedAt(BatchDateTimeSupport.utcNow()));

    // 新配置只影响新请求；持久化等待仍保留原始请求中的两条依赖边。
    fileGovernanceScheduler.manageFileArrivalGroups();
    var waitingRows = jdbcTemplate.queryForList(
        selectMetadataJsonSql,
        TENANT_ID,
        groupCode);
    assertThat(waitingRows).hasSize(1);
    assertThat(waitingRows.get(0).get("arrival_state")).isEqualTo("WAITING_ARRIVAL");

    insertFileRecord(new FileRecordSpec(
            TENANT_ID,
            "file-a.csv",
            "INPUT",
            FileStatus.RECEIVED.code(),
            "LOCAL",
            "incoming/" + groupCode + "/file-a.csv",
            metadata)
        .createdAt(BatchDateTimeSupport.utcNow())
        .updatedAt(BatchDateTimeSupport.utcNow()));
    fileGovernanceScheduler.manageFileArrivalGroups();

    var recoveredRows = jdbcTemplate.queryForList(
        selectMetadataJsonSql,
        TENANT_ID,
        groupCode);
    assertThat(recoveredRows).hasSize(2);
    assertThat(recoveredRows)
        .allSatisfy(row -> assertThat(row.get("arrival_state")).isEqualTo("TRIGGERED"));
  }

  @Test
  @DisplayName("Bundle 到达组只收到部分必需文件时不启动 Workflow")
  void shouldNotLaunchBundle_whenRequiredFileSetIsIncomplete() {
  final String selectMetadataJsonSql = """
      select metadata_json->>'arrivalState' from batch.file_record where tenant_id = ?\s\
      and metadata_json->>'fileGroupCode' = ?\
      """;
  final String selectMetadataJsonSql2 = """
      select metadata_json->>'arrivalReason' from batch.file_record where tenant_id = ?\s\
      and metadata_json->>'fileGroupCode' = ?\
      """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and request_id like ?\
        """;

    String groupCode = "partial-bundle-" + suffix();
    String workflowCode = "MISSING_WORKFLOW_" + suffix();
    String metadata = """
        {
          "fileGroupCode": "%s",
          "waitFileGroupMode": "ALL_OF",
          "requiredFileSet": "file-a.csv,file-b.csv",
          "arrivalTimeoutAction": "MANUAL_CONFIRM",
          "triggerOnComplete": true,
          "bundleWorkflowCode": "%s",
          "bundleTemplateCode": "TPL_PARTIAL"
        }
        """.formatted(groupCode, workflowCode);
    insertFileRecord(new FileRecordSpec(
        TENANT_ID,
        "file-a.csv",
        "INPUT",
        FileStatus.RECEIVED.code(),
        "LOCAL",
        "incoming/" + groupCode + "/file-a.csv",
        metadata));
    double bundleLaunchFailuresBefore = counterValue("batch.file.arrival.bundle.launch.failed");
    LocalDate bizDate = LocalDate.of(2026, Month.MARCH, 27);
    fileGovernanceScheduler.manageFileArrivalGroups();

    String selectedMetadataJson =
        jdbcTemplate.queryForObject(selectMetadataJsonSql, String.class, TENANT_ID, groupCode);
    assertThat(selectedMetadataJson).isEqualTo("WAITING_ARRIVAL");
    String selectedMetadataJsonRead2 =
        jdbcTemplate.queryForObject(selectMetadataJsonSql2, String.class, TENANT_ID, groupCode);
    assertThat(selectedMetadataJsonRead2).isEqualTo("WAITING_REQUIRED_FILES");
    Integer selectedTriggerRequestCount = jdbcTemplate.queryForObject(
        selectCountSql,
        Integer.class,
        TENANT_ID,
        "bundle-arrival-" + TENANT_ID + "-%-" + groupCode + "-" + bizDate);
    assertThat(selectedTriggerRequestCount).isZero();
    assertThat(counterValue("batch.file.arrival.bundle.launch.failed"))
        .isEqualTo(bundleLaunchFailuresBefore);
  }

  @Test
  @DisplayName("并发扫描时旧的缺文件快照不能覆盖新扫描的触发状态")
  void shouldNotRevertTriggeredGroup_whenOlderPartialScanFinishesLast() throws Exception {
    final String selectMetadataJsonSql = """
        select metadata_json->>'arrivalState' as arrival_state from batch.file_record where tenant_id = ?\s\
        and metadata_json->>'fileGroupCode' = ? order by file_name\
        """;
    final String selectCountSql = """
        select count(*) from batch.file_audit_log where tenant_id = ? and trace_id = ?\s\
        and operation_type = 'ARRIVAL_GROUP_WAITING_ARRIVAL'\
        """;

    String groupCode = "arrival-race-" + suffix();
    String metadata = """
        {"fileGroupCode":"%s","waitFileGroupMode":"ALL_OF","requiredFileSet":"file-a.csv,file-b.csv","triggerOnComplete":true}
        """.formatted(groupCode);
    insertFileRecord(new FileRecordSpec(
        TENANT_ID,
        "file-a.csv",
        "INPUT",
        FileStatus.RECEIVED.code(),
        "LOCAL",
        "incoming/" + groupCode + "/file-a.csv",
        metadata));

    CountDownLatch partialSnapshotRead = new CountDownLatch(1);
    CountDownLatch continuePartialScan = new CountDownLatch(1);
    AtomicBoolean pauseFirstRead = new AtomicBoolean(true);
    doAnswer(invocation -> {
          Object result = invocation.callRealMethod();
          if (pauseFirstRead.compareAndSet(true, false)) {
            partialSnapshotRead.countDown();
            if (!continuePartialScan.await(10, TimeUnit.SECONDS)) {
              throw new IllegalStateException("timed out waiting to resume stale arrival scan");
            }
          }
          return result;
        })
        .when(fileGovernanceRepositorySpy)
        .selectArrivalGovernanceCandidates(anyInt());

    var executor = Executors.newSingleThreadExecutor();
    try {
      var staleScan = executor.submit(fileGovernanceScheduler::manageFileArrivalGroups);
      assertThat(partialSnapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
      insertFileRecord(new FileRecordSpec(
          TENANT_ID,
          "file-b.csv",
          "INPUT",
          FileStatus.RECEIVED.code(),
          "LOCAL",
          "incoming/" + groupCode + "/file-b.csv",
          metadata));

      fileGovernanceScheduler.manageFileArrivalGroups();
      continuePartialScan.countDown();
      staleScan.get(20, TimeUnit.SECONDS);
    } finally {
      continuePartialScan.countDown();
      executor.shutdownNow();
    }

    var states = jdbcTemplate.queryForList(
        selectMetadataJsonSql,
        TENANT_ID,
        groupCode);
    assertThat(states).hasSize(2);
    assertThat(states)
        .allSatisfy(row -> assertThat(row.get("arrival_state")).isEqualTo("TRIGGERED"));
    Integer selectedFileAuditLogCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, TENANT_ID, "arrival-group-" + groupCode);
    assertThat(selectedFileAuditLogCount).isZero();
  }

  private double counterValue(String name) {
    var counter = meterRegistry.find(name).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private Long insertFileRecord(FileRecordSpec spec) {
    final String insertFileRecordSql = """
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
          """;

    Date bizDate = Date.valueOf(LocalDate.of(2026, Month.MARCH, 27));
    String traceId = "trace-" + suffix();
    Timestamp createdAt = Timestamp.from(spec.createdAt);
    Timestamp updatedAt = Timestamp.from(spec.updatedAt);
    return jdbcTemplate.queryForObject(
        insertFileRecordSql,
        Long.class,
        spec.tenantId,
        spec.fileCategory,
        spec.fileName,
        spec.fileName,
        spec.storageType,
        spec.storagePath,
        spec.storageBucket,
        spec.fileStatus,
        bizDate,
        traceId,
        spec.metadataJson,
        createdAt,
        updatedAt);
  }

  private void putObject(String objectName, String content) throws Exception {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    s3Client()
        .putObject(
            PutObjectRequest.builder()
                .bucket(s3Bucket())
                .key(objectName)
                .contentType("text/plain")
                .build(),
            RequestBody.fromInputStream(new ByteArrayInputStream(bytes), bytes.length));
  }

  private S3Client s3Client() {
    return S3Client.builder()
        .endpointOverride(URI.create(s3Endpoint()))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(s3AccessKey(), s3SecretKey())))
        .forcePathStyle(s3PathStyleEnabled())
        .region(Region.of(s3Region()))
        .build();
  }

  private String suffix() {
    return Long.toUnsignedString(System.nanoTime());
  }
}
