package io.github.pinpols.batch.worker.imports.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.mapper.ImportIngressOwnershipMapper;
import io.github.pinpols.batch.common.storage.FilesystemObjectStore;
import io.github.pinpols.batch.common.storage.ObjectListing;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipIdentity;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipRepository;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.core.config.WorkerDataSourceSupport;
import io.github.pinpols.batch.worker.core.infrastructure.FileAuditParam;
import io.github.pinpols.batch.worker.core.infrastructure.FileRecordParam;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import io.github.pinpols.batch.worker.imports.config.ImportScannerProperties;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import io.github.pinpols.batch.worker.imports.mapper.ImportIngressScanMapper;
import io.github.pinpols.batch.worker.imports.runtime.ImportIngressScanRepository.Lease;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("导入扫描持久进度与围栏")
class ImportIngressScanPersistenceIntegrationTest {
  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();

  private static JdbcTemplate jdbc;
  private static ImportIngressScanRepository scanRepository;
  private static PlatformFileRecordRepository fileRecords;
  private static PlatformFileAuditRepository fileAudits;

  @TempDir
  Path root;

  private String prefix;
  private ImportScannerProperties properties;
  private S3StorageProperties storage;
  private ImportWorkerConfiguration worker;
  private FilesystemObjectStore objectStore;
  private ImportIngressOwnershipIdentity ownershipIdentity;

  @BeforeAll
  static void setUpDatabase() throws Exception {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load()
        .migrate();
    jdbc = new JdbcTemplate(dataSource);
    SqlSessionTemplate session =
        new SqlSessionTemplate(WorkerDataSourceSupport.buildPlatformSqlSessionFactory(dataSource));
    ImportIngressScanMapper scans = session.getMapper(ImportIngressScanMapper.class);
    ImportIngressOwnershipMapper ownership = session.getMapper(ImportIngressOwnershipMapper.class);
    scanRepository = new ImportIngressScanRepository(
        scans,
        new ImportIngressOwnershipRepository(ownership),
        new DataSourceTransactionManager(dataSource));
    PlatformFileRuntimeMapper mapper = session.getMapper(PlatformFileRuntimeMapper.class);
    fileRecords = new PlatformFileRecordRepository(mapper);
    fileAudits = new PlatformFileAuditRepository(mapper);
  }

  @BeforeEach
  void setUp() {
    prefix = "ingress/" + UUID.randomUUID() + "/";
    properties = new ImportScannerProperties();
    properties.setPrefix(prefix);
    properties.setBatchSize(1);
    properties.setStabilityWindowSeconds(0);
    properties.setDefaultBizDate("2026-11-09");
    storage = new S3StorageProperties();
    storage.setBucket("batch-test");
    worker = new ImportWorkerConfiguration(
        null,
        "IMPORT",
        "t1",
        15000L,
        "topic",
        "group",
        null,
        new ImportWorkerConfiguration.FileProcessing(false, 1000, 1000, 500),
        null);
    objectStore = new FilesystemObjectStore(root.toString(), "", "test-secret");
    ownershipIdentity = ImportIngressIdentityFixtures.filesystemIdentity(root);
  }

  @Test
  @DisplayName("到达列表末页后须处理完整登记快照才能推进周期")
  void scan_shouldFinishApplySnapshot_beforeCleaningCycle() throws Exception {
    properties.setStabilityWindowSeconds(3600);
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    for (int i = 0; i < 4; i++) {
      Files.writeString(directory.resolve("file-" + i + ".csv"), "data");
    }
    ImportIngressScanner scanner = scanner();
    String ageSql = """
        UPDATE batch.import_ingress_scan_object
           SET first_observed_at = clock_timestamp() - interval '2 hours'
         WHERE scope_id = ?
        """;
    String cycleSql = "SELECT cycle FROM batch.import_ingress_scan_state WHERE scope_id = ?";
    long cycle = 1;
    for (int tick = 0; tick < 50 && cycle == 1; tick++) {
      scanner.scan();
      jdbc.update(ageSql, scanner.scanScope());
      cycle = jdbc.queryForObject(cycleSql, Long.class, scanner.scanScope());
    }
    assertThat(cycle).isEqualTo(2);
    for (int i = 0; i < 4; i++) {
      String objectKey = prefix + "t1/file-" + i + ".csv";
      assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey))
          .as("首周期结束前登记 %s", objectKey)
          .isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACTIVE", "RETIRED"})
  @DisplayName("父扫描范围不能绕过子范围的权威清单或退休保护")
  void parentScanner_shouldNotRegisterObjectsOwnedByNestedScope(String nestedState)
      throws Exception {
    String nestedPrefix = prefix + "t1/";
    String objectKey = nestedPrefix + "orders.csv";
    Path directory = root.resolve(storage.getBucket()).resolve(nestedPrefix);
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("orders.csv"), "customer-no,customer-name\n1,客户\n");
    Path parentDirectory = root.resolve(storage.getBucket()).resolve(prefix).resolve("t2");
    Files.createDirectories(parentDirectory);
    Files.writeString(parentDirectory.resolve("parent.csv"), "data");

    properties.setStabilityWindowSeconds(3600);
    ImportIngressScanner parent = scanner();
    parent.scan();
    ImportScannerProperties nestedProperties = new ImportScannerProperties();
    nestedProperties.setPrefix(nestedPrefix);
    nestedProperties.setBatchManifestEnabled(true);
    nestedProperties.setDefaultBizDate("2026-11-09");
    nestedProperties.setManifestBindings(List.of(manifestBinding(
        "t1", nestedPrefix, "GROUP1", "manifests/" + UUID.randomUUID() + "/{bizDate}.batch.json")));
    ImportIngressScanner nested = new ImportIngressScanner(
        fileRecords,
        fileAudits,
        worker,
        nestedProperties,
        storage,
        objectStore,
        new ObjectMapper(),
        scanRepository,
        ownershipIdentity);
    declareOwnership(nested, nestedProperties);
    String setStateSql = "UPDATE batch.import_ingress_ownership SET state = ? WHERE scope_id = ?";
    jdbc.update(setStateSql, nestedState, nested.scanScope());

    String ageSql = """
        UPDATE batch.import_ingress_scan_object
           SET first_observed_at = clock_timestamp() - interval '2 hours'
         WHERE scope_id = ?
        """;
    for (int tick = 0; tick < 20; tick++) {
      jdbc.update(ageSql, parent.scanScope());
      parent.scan();
    }

    boolean registered =
        fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey);
    assertThat(registered).as("子范围 %s 且权威清单缺失时，父扫描不得登记", nestedState).isFalse();
    boolean parentRegistered = fileRecords.existsFileRecordByStoragePath(
        "t2", storage.getBucket(), prefix + "t2/parent.csv");
    assertThat(parentRegistered).as("父范围自己的文件仍可登记").isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACTIVE", "RETIRED"})
  @DisplayName("父扫描范围不能把子范围的目录外权威清单登记为导出触发文件")
  void parentScanner_shouldNotRegisterNestedScopeAuthorityAsExportTrigger(String nestedState)
      throws Exception {
    String nestedPrefix = prefix + "t1/data/";
    String authorityKey = prefix + "t1/authority-2026-11-09.batch.json";
    Path directory = root.resolve(storage.getBucket()).resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("authority-2026-11-09.batch.json"), """
        {"schemaVersion":"batch-manifest-v2","tenantId":"t1","fileGroupCode":"EXPORTS",
         "bizDate":"2026-11-09","workflowCode":"WF_EXPORT",
         "fileMapping":[{"fileName":"output.csv","templateCode":"TPL_EXPORT"}]}
        """);

    properties.setBatchManifestEnabled(true);
    properties.getArrival().setEnabled(true);
    properties.setStabilityWindowSeconds(3600);
    ImportIngressScanner parent = scanner();
    parent.scan();
    ImportScannerProperties nestedProperties = new ImportScannerProperties();
    nestedProperties.setPrefix(nestedPrefix);
    nestedProperties.setBatchManifestEnabled(true);
    nestedProperties.setDefaultBizDate("2026-11-09");
    nestedProperties.setManifestBindings(List.of(manifestBinding(
        "t1", nestedPrefix, "EXPORTS", prefix + "t1/authority-{bizDate}.batch.json")));
    ImportIngressScanner nested = new ImportIngressScanner(
        fileRecords,
        fileAudits,
        worker,
        nestedProperties,
        storage,
        objectStore,
        new ObjectMapper(),
        scanRepository,
        ownershipIdentity);
    declareOwnership(nested, nestedProperties);
    String setStateSql = "UPDATE batch.import_ingress_ownership SET state = ? WHERE scope_id = ?";
    jdbc.update(setStateSql, nestedState, nested.scanScope());

    String ageSql = """
        UPDATE batch.import_ingress_scan_object
           SET first_observed_at = clock_timestamp() - interval '2 hours'
         WHERE scope_id = ?
        """;
    for (int tick = 0; tick < 10; tick++) {
      jdbc.update(ageSql, parent.scanScope());
      parent.scan();
    }

    boolean registered =
        fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), authorityKey);
    assertThat(registered).as("子范围 %s 的权威清单不应由父扫描登记", nestedState).isFalse();
  }

  @Test
  @DisplayName("对象持续到达且前向游标所指对象删除时回扫仍发现更早插入的文件")
  void scan_shouldDiscoverEarlierInsertion_whenMarkerDeletedAndObjectsKeepArriving()
      throws Exception {
    properties.setBatchSize(2);
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("010.csv"), "data");
    Files.writeString(directory.resolve("020.csv"), "data");
    Files.writeString(directory.resolve("030.csv"), "data");
    ImportIngressScanner scanner = scanner();
    scanner.scan();
    Files.delete(directory.resolve("020.csv"));
    Files.writeString(directory.resolve("015.csv"), "data");
    String earlier = prefix + "t1/015.csv";
    for (int tick = 0;
        tick < 60 && !fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), earlier);
        tick++) {
      for (int i = 0; i < 3; i++) {
        Files.writeString(directory.resolve("%04d.csv".formatted(1000 + tick * 3 + i)), "data");
      }
      scanner.scan();
    }
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), earlier))
        .isTrue();
  }

  @Test
  @DisplayName("重建扫描器后跨页发现数据、完成标记和批次清单")
  void scan_shouldResumeAcrossPagesAndMatchDoneAndManifest() throws Exception {
    properties.setRequireDoneFile(true);
    properties.setBatchManifestEnabled(true);
    properties.getArrival().setEnabled(true);
    properties.setManifestBindings(List.of(
        manifestBinding("t1", prefix + "t1/", "GROUP1", prefix + "t1/z-{bizDate}.batch.json")));
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("orders.csv"), "order-data");
    Files.writeString(directory.resolve("orders.csv.done"), "");
    Files.writeString(directory.resolve("z-2026-11-09.batch.json"), """
        {"schemaVersion":"batch-manifest-v1","tenantId":"t1",
         "fileGroupCode":"GROUP1","bizDate":"2026-11-09","requiredFiles":["orders.csv"]}
        """);
    ImportIngressScanner beforeRestart = scanner();
    beforeRestart.scan();
    String scope = beforeRestart.scanScope();
    String markerSql = "SELECT list_marker FROM batch.import_ingress_scan_state WHERE scope_id = ?";
    String marker = jdbc.queryForObject(markerSql, String.class, scope);
    assertThat(marker).isNotNull();

    ImportIngressScanner afterRestart = scanner();
    String objectKey = prefix + "t1/orders.csv";
    for (int i = 0;
        i < 30 && !fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey);
        i++) {
      afterRestart.scan();
    }

    Map<String, Object> row =
        fileRecords.loadFileRecordByStoragePath("t1", storage.getBucket(), objectKey);
    assertThat(row).isNotEmpty();
    assertThat(row.get("metadata_json").toString()).contains("GROUP1", "orders.csv");
    String countSql =
        "SELECT count(*) FROM batch.file_record WHERE tenant_id = ? AND storage_path = ?";
    Long count = jdbc.queryForObject(countSql, Long.class, "t1", objectKey);
    assertThat(count).isEqualTo(1L);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("清单变更回填束绑定时遵循到达治理开关")
  void scan_shouldRespectArrivalSwitch_whenBackfillingManifest(boolean arrivalEnabled)
      throws Exception {
    properties.setBatchSize(2);
    properties.setBatchManifestEnabled(true);
    properties.getArrival().setEnabled(arrivalEnabled);
    properties.setManifestBindings(List.of(
        manifestBinding("t1", prefix + "t1/", "GROUP1", prefix + "t1/auth-{bizDate}.batch.json")));
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("orders.csv"), "data");
    Path authority = directory.resolve("auth-2026-11-09.batch.json");
    Files.writeString(authority, """
        {"schemaVersion":"batch-manifest-v1","tenantId":"t1","fileGroupCode":"GROUP1",
         "bizDate":"2026-11-09","requiredFiles":["orders.csv"]}
        """);
    ImportIngressScanner scanner = scanner();
    String objectKey = prefix + "t1/orders.csv";
    for (int tick = 0;
        tick < 40
            && !fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey);
        tick++) {
      scanner.scan();
    }
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey))
        .isTrue();

    Files.writeString(authority, """
        {"schemaVersion":"batch-manifest-v2","tenantId":"t1","fileGroupCode":"GROUP1",
         "bizDate":"2026-11-09","requiredFiles":["orders.csv"],"workflowCode":"WF_IMPORT",
         "fileMapping":[{"fileName":"orders.csv","templateCode":"TPL_ORDERS"}]}
        """);
    Map<String, Object> row = Map.of();
    for (int tick = 0; tick < 60; tick++) {
      scanner.scan();
      row = fileRecords.loadFileRecordByStoragePath("t1", storage.getBucket(), objectKey);
      if (row.get("metadata_json").toString().contains("WF_IMPORT")) {
        break;
      }
    }
    String metadata = row.get("metadata_json").toString();
    assertThat(metadata)
        .contains("bundleWorkflowCode", "WF_IMPORT", "bundleTemplateCode", "TPL_ORDERS");
    if (arrivalEnabled) {
      assertThat(metadata).contains("fileGroupCode", "GROUP1", "requiredFileSet", "arrivalState");
    } else {
      assertThat(metadata)
          .doesNotContain("fileGroupCode", "requiredFileSet", "arrivalState", "triggerOnComplete");
    }
  }

  @Test
  @DisplayName("扫描接管同租户已对账文件时保留记录并补齐可信身份")
  void scan_shouldAdoptReconciledFile_afterAuthoritativeManifest() throws Exception {
    properties.setBatchSize(2);
    properties.setBatchManifestEnabled(true);
    properties.setDefaultBizType("LOAD_TEST");
    properties.getArrival().setEnabled(true);
    properties.setManifestBindings(List.of(manifestBinding(
        "t1", prefix + "t1/", "GROUP1", prefix + "t1/authority-{bizDate}.batch.json")));
    ImportScannerProperties.ChannelBinding channel = new ImportScannerProperties.ChannelBinding();
    channel.setTenantId("t1");
    channel.setObjectPrefix(prefix + "t1/");
    channel.setChannelCode("channel_1");
    properties.setChannelBindings(List.of(channel));
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("a.csv"), "first");
    Files.writeString(directory.resolve("b.csv"), "second");
    String firstKey = prefix + "t1/a.csv";
    String secondKey = prefix + "t1/b.csv";
    var firstObject =
        objectStore.list(storage.getBucket(), firstKey, null, 1).objects().get(0);
    FileRecordParam reconciled = FileRecordParam.builder()
        .tenantId("t1")
        .fileCategory("INPUT")
        .fileName("a.csv")
        .fileFormatType("DELIMITED")
        .fileSizeBytes(firstObject.size())
        .storageType("S3")
        .storageBucket(storage.getBucket())
        .storagePath(firstKey)
        .sourceType("SYSTEM")
        .fileStatus("RECEIVED")
        .metadata(Map.of(
            "reconciled",
            true,
            "etag",
            firstObject.etag(),
            "bundleWorkflowCode",
            "EXISTING_WORKFLOW"))
        .build();
    Long originalId = fileRecords.createFileRecord(reconciled);
    ImportIngressScanner scanner = scanner();
    for (int tick = 0; tick < 8; tick++) {
      scanner.scan();
    }
    String selectIdentitySql = """
        SELECT id, biz_date, biz_type, arrival_channel_code, legacy_channel_fallback, metadata_json::text AS metadata
          FROM batch.file_record WHERE tenant_id = ? AND storage_bucket = ? AND storage_path = ?
        """;
    Map<String, Object> beforeManifest =
        jdbc.queryForMap(selectIdentitySql, "t1", storage.getBucket(), firstKey);
    assertThat(beforeManifest.get("biz_date")).isNull();
    assertThat(beforeManifest.get("arrival_channel_code")).isNull();
    assertThat(beforeManifest.get("metadata").toString()).doesNotContain("fileGroupCode");
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), secondKey))
        .isFalse();

    Files.writeString(directory.resolve("authority-2026-11-09.batch.json"), """
        {"schemaVersion":"batch-manifest-v2","tenantId":"t1","fileGroupCode":"GROUP1",
         "bizDate":"2026-11-09","requiredFiles":["a.csv","b.csv"],"workflowCode":"EXISTING_WORKFLOW"}
        """);
    for (int tick = 0; tick < 60; tick++) {
      scanner.scan();
    }
    Map<String, Object> first =
        jdbc.queryForMap(selectIdentitySql, "t1", storage.getBucket(), firstKey);
    Map<String, Object> second =
        jdbc.queryForMap(selectIdentitySql, "t1", storage.getBucket(), secondKey);
    assertThat(((Number) first.get("id")).longValue()).isEqualTo(originalId);
    assertThat(first.get("biz_date")).hasToString("2026-11-09");
    assertThat(first.get("biz_type")).isEqualTo("LOAD_TEST");
    assertThat(first.get("arrival_channel_code")).isEqualTo("channel_1");
    assertThat(first.get("legacy_channel_fallback")).isEqualTo(false);
    assertThat(first.get("metadata").toString())
        .contains("GROUP1", "WAITING_ARRIVAL", "EXISTING_WORKFLOW");
    assertThat(second.get("biz_date")).hasToString("2026-11-09");
    assertThat(second.get("arrival_channel_code")).isEqualTo("channel_1");
    String countSql =
        "SELECT count(*) FROM batch.file_record WHERE tenant_id = ? AND storage_path = ?";
    Long count = jdbc.queryForObject(countSql, Long.class, "t1", firstKey);
    assertThat(count).isEqualTo(1L);
    String auditSql = """
        SELECT count(*) FROM batch.file_audit_log
         WHERE tenant_id = ? AND file_id = ? AND operation_type = ?
        """;
    Long arrivalAudits =
        jdbc.queryForObject(auditSql, Long.class, "t1", originalId, "ARRIVAL_REGISTER");
    Long scanAudits = jdbc.queryForObject(auditSql, Long.class, "t1", originalId, "RECEIVE_SCAN");
    assertThat(arrivalAudits).isEqualTo(1L);
    assertThat(scanAudits).isEqualTo(1L);
  }

  @Test
  @DisplayName("接管时保留已有业务类型，关闭到达治理只记录一次扫描审计")
  void scan_shouldPreserveBizTypeAndNotAuditDisabledArrival() throws Exception {
    properties.setBatchSize(2);
    properties.setDefaultBizType("NEW_TYPE");
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("a.csv"), "first");
    String key = prefix + "t1/a.csv";
    var object = objectStore.list(storage.getBucket(), key, null, 1).objects().get(0);
    FileRecordParam reconciled = FileRecordParam.builder()
        .tenantId("t1")
        .bizType("EXISTING_TYPE")
        .fileCategory("INPUT")
        .fileName("a.csv")
        .fileFormatType("DELIMITED")
        .fileSizeBytes(object.size())
        .storageType("S3")
        .storageBucket(storage.getBucket())
        .storagePath(key)
        .sourceType("SYSTEM")
        .fileStatus("RECEIVED")
        .metadata(Map.of("reconciled", true, "etag", object.etag()))
        .build();
    Long id = fileRecords.createFileRecord(reconciled);
    ImportIngressScanner scanner = scanner();
    for (int tick = 0; tick < 20; tick++) {
      scanner.scan();
    }
    String typeSql = "SELECT biz_type FROM batch.file_record WHERE id = ?";
    String bizType = jdbc.queryForObject(typeSql, String.class, id);
    assertThat(bizType).isEqualTo("EXISTING_TYPE");
    String auditSql =
        "SELECT count(*) FROM batch.file_audit_log WHERE file_id = ? AND operation_type = ?";
    Long scanAudits = jdbc.queryForObject(auditSql, Long.class, id, "RECEIVE_SCAN");
    Long arrivalAudits = jdbc.queryForObject(auditSql, Long.class, id, "ARRIVAL_REGISTER");
    assertThat(scanAudits).isEqualTo(1L);
    assertThat(arrivalAudits).isZero();
  }

  @Test
  @DisplayName("接管审计失败时身份、元数据及全部审计共同回滚")
  void scan_shouldRollBackReconciledAdoption_whenAuditFails() throws Exception {
    properties.setBatchSize(2);
    properties.setDefaultBizType("LOAD_TEST");
    properties.getArrival().setEnabled(true);
    properties.getArrival().setFileGroupCode("GROUP1");
    properties.getArrival().setRequiredFileSet("a.csv");
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("a.csv"), "first");
    String key = prefix + "t1/a.csv";
    var object = objectStore.list(storage.getBucket(), key, null, 1).objects().get(0);
    FileRecordParam reconciled = FileRecordParam.builder()
        .tenantId("t1")
        .fileCategory("INPUT")
        .fileName("a.csv")
        .fileFormatType("DELIMITED")
        .fileSizeBytes(object.size())
        .storageType("S3")
        .storageBucket(storage.getBucket())
        .storagePath(key)
        .sourceType("SYSTEM")
        .fileStatus("RECEIVED")
        .metadata(Map.of("reconciled", true, "etag", object.etag()))
        .build();
    Long id = fileRecords.createFileRecord(reconciled);
    PlatformFileAuditRepository failingAudits = spy(fileAudits);
    doAnswer(invocation -> {
          FileAuditParam audit = invocation.getArgument(0);
          invocation.callRealMethod();
          if ("RECEIVE_SCAN".equals(audit.getOperationType())) {
            throw new IllegalStateException("验证接管审计回滚");
          }
          return null;
        })
        .when(failingAudits)
        .appendAudit(any(FileAuditParam.class));
    ImportIngressScanner scanner = new ImportIngressScanner(
        fileRecords,
        failingAudits,
        worker,
        properties,
        storage,
        objectStore,
        new ObjectMapper(),
        scanRepository,
        ownershipIdentity);
    declareOwnership(scanner, properties);
    assertThatThrownBy(() -> {
          for (int tick = 0; tick < 20; tick++) {
            scanner.scan();
          }
        })
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("验证接管审计回滚");
    String selectSql =
        "SELECT biz_date, biz_type, metadata_json::text AS metadata FROM batch.file_record WHERE id = ?";
    Map<String, Object> row = jdbc.queryForMap(selectSql, id);
    assertThat(row.get("biz_date")).isNull();
    assertThat(row.get("biz_type")).isNull();
    assertThat(row.get("metadata").toString()).doesNotContain("scannerAccepted", "fileGroupCode");
    String auditSql = "SELECT count(*) FROM batch.file_audit_log WHERE file_id = ?";
    Long auditCount = jdbc.queryForObject(auditSql, Long.class, id);
    assertThat(auditCount).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"legacy", "date", "channel", "workflow"})
  @DisplayName("对账文件已有身份冲突或历史回退资格时不自动接管")
  void scan_shouldKeepReconciledFile_whenIdentityCannotBeAdopted(String caseName) throws Exception {
    properties.setBatchSize(2);
    properties.setBatchManifestEnabled(true);
    properties.getArrival().setEnabled(true);
    properties.setManifestBindings(List.of(manifestBinding(
        "t1", prefix + "t1/", "GROUP1", prefix + "t1/authority-{bizDate}.batch.json")));
    ImportScannerProperties.ChannelBinding channel = new ImportScannerProperties.ChannelBinding();
    channel.setTenantId("t1");
    channel.setObjectPrefix(prefix + "t1/");
    channel.setChannelCode("channel_1");
    properties.setChannelBindings(List.of(channel));
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("a.csv"), "first");
    String key = prefix + "t1/a.csv";
    var object = objectStore.list(storage.getBucket(), key, null, 1).objects().get(0);
    String existingWorkflow = "workflow".equals(caseName) ? "EXISTING" : "WF";
    FileRecordParam reconciled = FileRecordParam.builder()
        .tenantId("t1")
        .fileCategory("INPUT")
        .fileName("a.csv")
        .fileFormatType("DELIMITED")
        .fileSizeBytes(object.size())
        .storageType("S3")
        .storageBucket(storage.getBucket())
        .storagePath(key)
        .sourceType("SYSTEM")
        .fileStatus("RECEIVED")
        .metadata(Map.of(
            "reconciled", true, "etag", object.etag(), "bundleWorkflowCode", existingWorkflow))
        .build();
    Long id = fileRecords.createFileRecord(reconciled);
    String prepareSql = """
        UPDATE batch.file_record SET legacy_channel_fallback = ?, biz_date = ?, arrival_channel_code = ?
         WHERE id = ?
        """;
    LocalDate existingDate = "date".equals(caseName) ? LocalDate.parse("2026-11-08") : null;
    String existingChannel = "channel".equals(caseName) ? "other_channel" : null;
    jdbc.update(prepareSql, "legacy".equals(caseName), existingDate, existingChannel, id);
    Files.writeString(directory.resolve("authority-2026-11-09.batch.json"), """
        {"schemaVersion":"batch-manifest-v2","tenantId":"t1","fileGroupCode":"GROUP1",
         "bizDate":"2026-11-09","requiredFiles":["a.csv"],"workflowCode":"WF"}
        """);
    ImportIngressScanner scanner = scanner();
    for (int tick = 0; tick < 20; tick++) {
      scanner.scan();
    }
    String selectSql = """
        SELECT biz_date, arrival_channel_code, legacy_channel_fallback, metadata_json::text AS metadata
          FROM batch.file_record WHERE id = ?
        """;
    Map<String, Object> result = jdbc.queryForMap(selectSql, id);
    assertThat(result.get("biz_date"))
        .isEqualTo(existingDate == null ? null : Date.valueOf(existingDate));
    assertThat(result.get("arrival_channel_code")).isEqualTo(existingChannel);
    assertThat(result.get("legacy_channel_fallback")).isEqualTo("legacy".equals(caseName));
    assertThat(result.get("metadata").toString())
        .contains(existingWorkflow)
        .doesNotContain("fileGroupCode", "scannerAccepted");
  }

  @Test
  @DisplayName("接管对账文件时必须先验证完成标记的原始文件摘要")
  void scan_shouldVerifySidecar_beforeAdoptingReconciledFile() throws Exception {
    properties.setBatchSize(3);
    properties.setRequireDoneFile(true);
    properties.setDoneFileFormat(ImportScannerProperties.DoneFileFormat.MANIFEST);
    properties.getArrival().setEnabled(true);
    properties.getArrival().setFileGroupCode("GROUP1");
    properties.getArrival().setRequiredFileSet("a.csv");
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("a.csv"), "first");
    String key = prefix + "t1/a.csv";
    var object = objectStore.list(storage.getBucket(), key, null, 1).objects().get(0);
    FileRecordParam reconciled = FileRecordParam.builder()
        .tenantId("t1")
        .fileCategory("INPUT")
        .fileName("a.csv")
        .fileFormatType("DELIMITED")
        .fileSizeBytes(object.size())
        .storageType("S3")
        .storageBucket(storage.getBucket())
        .storagePath(key)
        .sourceType("SYSTEM")
        .fileStatus("RECEIVED")
        .metadata(Map.of("reconciled", true, "etag", object.etag()))
        .build();
    Long id = fileRecords.createFileRecord(reconciled);
    String sidecarTemplate = """
        {"schemaVersion":"file-sidecar-manifest-v1","fileName":"a.csv","sizeBytes":5,
         "checksumType":"SHA-256","checksumValue":"%s","checksumScope":"RAW_OBJECT"}
        """;
    Path marker = directory.resolve("a.csv.done");
    Files.writeString(marker, sidecarTemplate.formatted("0".repeat(64)));
    ImportIngressScanner scanner = scanner();
    for (int tick = 0; tick < 15; tick++) {
      scanner.scan();
    }
    String selectSql = """
        SELECT biz_date, checksum_type, checksum_value, metadata_json::text AS metadata
          FROM batch.file_record WHERE id = ?
        """;
    Map<String, Object> invalid = jdbc.queryForMap(selectSql, id);
    assertThat(invalid.get("biz_date")).isNull();
    assertThat(invalid.get("checksum_value")).isNull();
    assertThat(invalid.get("metadata").toString())
        .doesNotContain("fileGroupCode", "scannerAccepted");

    Files.writeString(
        marker,
        sidecarTemplate.formatted(
            "a7937b64b8caa58f03721bb6bacf5c78cb235febe0e70b1b84cd99541461a08e"));
    for (int tick = 0; tick < 20; tick++) {
      scanner.scan();
    }
    Map<String, Object> valid = jdbc.queryForMap(selectSql, id);
    assertThat(valid.get("biz_date")).hasToString("2026-11-09");
    assertThat(valid.get("checksum_type")).isEqualTo("SHA-256");
    assertThat(valid.get("checksum_value"))
        .isEqualTo("a7937b64b8caa58f03721bb6bacf5c78cb235febe0e70b1b84cd99541461a08e");
    assertThat(valid.get("metadata").toString()).contains("GROUP1", "VERIFIED", "scannerAccepted");
  }

  @Test
  @DisplayName("只有共享归属范围处于 ACTIVE 时才扫描对象")
  void scan_shouldStartOnly_afterOwnershipIsActive() throws Exception {
    properties.setBatchSize(3);
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("owned.csv"), "content");
    String key = prefix + "t1/owned.csv";
    ImportIngressScanner scanner = new ImportIngressScanner(
        fileRecords,
        fileAudits,
        worker,
        properties,
        storage,
        objectStore,
        new ObjectMapper(),
        scanRepository,
        ownershipIdentity);
    assertThat(scanner.scan()).isEqualTo(ImportIngressScanner.ScanResult.OWNERSHIP_INACTIVE);
    String stateSql = "SELECT count(*) FROM batch.import_ingress_scan_state WHERE scope_id = ?";
    assertThat(jdbc.queryForObject(stateSql, Long.class, scanner.scanScope())).isZero();
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), key))
        .isFalse();
    String declareSql = """
        INSERT INTO batch.import_ingress_ownership(scope_id, storage_id, bucket_name, object_prefix, state)
        VALUES (?, ?, ?, ?, 'RETIRED')
        """;
    jdbc.update(
        declareSql,
        scanner.scanScope(),
        scanner.scanStorageIdentity(),
        storage.getBucket(),
        prefix);
    assertThat(scanner.scan()).isEqualTo(ImportIngressScanner.ScanResult.OWNERSHIP_INACTIVE);
    String activateSql =
        "UPDATE batch.import_ingress_ownership SET state = 'ACTIVE' WHERE scope_id = ?";
    jdbc.update(activateSql, scanner.scanScope());
    for (int tick = 0;
        tick < 10 && !fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), key);
        tick++) {
      scanner.scan();
    }
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), key))
        .isTrue();
  }

  @Test
  @DisplayName("共享声明中的无效渠道不能进入扫描")
  void scan_shouldRejectInvalidDeclaredChannel_beforeScanning() throws Exception {
    ImportIngressScanner scanner = scanner();
    String bindingSql = """
        INSERT INTO batch.import_ingress_ownership_binding
          (scope_id, binding_type, tenant_id, object_prefix, channel_code)
        VALUES (?, 'CHANNEL', 't1', ?, '9invalid')
        """;
    jdbc.update(bindingSql, scanner.scanScope(), prefix + "t1/");
    assertThatThrownBy(scanner::scan)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("渠道编码无效");
    String stateSql = "SELECT count(*) FROM batch.import_ingress_scan_state WHERE scope_id = ?";
    assertThat(jdbc.queryForObject(stateSql, Long.class, scanner.scanScope())).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenant", "directory", "group", "placeholder", "suffix", "traversal"})
  @DisplayName("共享声明中的无效清单绑定不能进入扫描")
  void scan_shouldRejectInvalidDeclaredManifest_beforeScanning(String variant) {
    ImportIngressScanner scanner = scanner();
    String tenant = "tenant".equals(variant) ? " " : "t1";
    String directory = "directory".equals(variant) ? "other/t1/" : prefix + "t1/";
    String group = "group".equals(variant) ? "9invalid" : "GROUP1";
    String key =
        switch (variant) {
          case "placeholder" -> "manifest/fixed.batch.json";
          case "suffix" -> "manifest/{bizDate}.json";
          case "traversal" -> "manifest/../{bizDate}.batch.json";
          default -> "manifest/{bizDate}.batch.json";
        };
    String bindingSql = """
        INSERT INTO batch.import_ingress_ownership_binding
          (scope_id, binding_type, tenant_id, object_prefix, file_group_code, manifest_key_template)
        VALUES (?, 'MANIFEST', ?, ?, ?, ?)
        """;
    jdbc.update(bindingSql, scanner.scanScope(), tenant, directory, group, key);
    assertThatThrownBy(scanner::scan).isInstanceOf(IllegalStateException.class);
    String stateSql = "SELECT count(*) FROM batch.import_ingress_scan_state WHERE scope_id = ?";
    assertThat(jdbc.queryForObject(stateSql, Long.class, scanner.scanScope())).isZero();
  }

  @Test
  @DisplayName("扫描开始前存在跨窗口其他清单时只按配置的权威清单登记")
  void scan_shouldUseOnlyAuthoritativeManifest_acrossWindows() throws Exception {
    properties.setBatchSize(1);
    properties.setBatchManifestEnabled(true);
    properties.getArrival().setEnabled(true);
    properties.setManifestBindings(List.of(
        manifestBinding("t1", prefix + "t1/", "GROUP1", prefix + "t1/c-{bizDate}.batch.json")));
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("a-orders.csv"), "data");
    String manifest = """
        {"schemaVersion":"batch-manifest-v1","tenantId":"t1","fileGroupCode":"%s",
         "bizDate":"2026-11-09","requiredFiles":["a-orders.csv"]}
        """;
    Files.writeString(directory.resolve("b-first.batch.json"), manifest.formatted("GROUP2"));
    Files.writeString(directory.resolve("c-2026-11-09.batch.json"), manifest.formatted("GROUP1"));
    ImportIngressScanner scanner = scanner();
    String objectKey = prefix + "t1/a-orders.csv";

    for (int tick = 0;
        tick < 30
            && !fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey);
        tick++) {
      scanner.scan();
    }
    Map<String, Object> row =
        fileRecords.loadFileRecordByStoragePath("t1", storage.getBucket(), objectKey);
    assertThat(row).isNotEmpty();
    assertThat(row.get("metadata_json").toString()).contains("GROUP1").doesNotContain("GROUP2");
  }

  @Test
  @DisplayName("未绑定、缺失或身份不符的权威清单不登记，修正后恢复")
  void scan_shouldWaitForBoundManifest_withMatchingIdentity() throws Exception {
    properties.setBatchSize(2);
    properties.setBatchManifestEnabled(true);
    properties.getArrival().setEnabled(true);
    properties.getArrival().setFileGroupCode("STATIC");
    properties.getArrival().setRequiredFileSet("orders.csv");
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("orders.csv"), "data");
    String objectKey = prefix + "t1/orders.csv";
    ImportIngressScanner unbound = scanner();
    for (int tick = 0; tick < 8; tick++) {
      unbound.scan();
    }
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey))
        .isFalse();

    // 配置语义变更在生产须先经过受控维护切换；本例为新扫描范围创建独立状态。
    prefix = "ingress/" + UUID.randomUUID() + "/";
    properties.setPrefix(prefix);
    properties.setManifestBindings(List.of(manifestBinding(
        "t1", prefix + "t1/", "GROUP1", prefix + "t1/authority-{bizDate}.batch.json")));
    ImportIngressScanner scanner = scanner();
    directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("orders.csv"), "data");
    objectKey = prefix + "t1/orders.csv";
    for (int tick = 0; tick < 8; tick++) {
      scanner.scan();
    }
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey))
        .isFalse();
    Path authority = directory.resolve("authority-2026-11-09.batch.json");
    String template = """
        {"schemaVersion":"batch-manifest-v1","tenantId":"%s","fileGroupCode":"%s",
         "bizDate":"%s","requiredFiles":["%s"]}
        """;
    List<String> invalidManifests = List.of(
        template.formatted("other", "GROUP1", "2026-11-09", "orders.csv"),
        template.formatted("t1", "WRONG", "2026-11-09", "orders.csv"),
        template.formatted("t1", "GROUP1", "2026-11-08", "orders.csv"),
        template.formatted("t1", "GROUP1", "invalid-date", "orders.csv"),
        template.formatted("t1", "GROUP1", "2026-11-09", "other.csv"));
    for (int variant = 0; variant < invalidManifests.size(); variant++) {
      Files.writeString(authority, invalidManifests.get(variant) + " ".repeat(variant));
      for (int tick = 0; tick < 8; tick++) {
        scanner.scan();
      }
      assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey))
          .as("错误清单身份变体 %s 不得登记", variant)
          .isFalse();
    }
    Files.writeString(authority, """
        {"schemaVersion":"batch-manifest-v1","tenantId":"t1","fileGroupCode":"GROUP1",
         "bizDate":"2026-11-09","requiredFiles":["orders.csv"],"generatedAt":"valid"}
        """);
    for (int tick = 0;
        tick < 40
            && !fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey);
        tick++) {
      scanner.scan();
    }
    Map<String, Object> row =
        fileRecords.loadFileRecordByStoragePath("t1", storage.getBucket(), objectKey);
    assertThat(row).isNotEmpty();
    assertThat(row.get("metadata_json").toString()).contains("GROUP1").doesNotContain("STATIC");
  }

  @Test
  @DisplayName("权威清单读取后删除时不使用旧内容，恢复后可登记")
  void scan_shouldWait_whenAuthoritativeManifestDisappearsDuringRead() throws Exception {
    properties.setBatchSize(10);
    properties.setBatchManifestEnabled(true);
    properties.getArrival().setEnabled(true);
    properties.setManifestBindings(List.of(
        manifestBinding("t1", prefix + "t1/", "GROUP1", prefix + "t1/auth-{bizDate}.batch.json")));
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("orders.csv"), "data");
    Path authority = directory.resolve("auth-2026-11-09.batch.json");
    String content = """
        {"schemaVersion":"batch-manifest-v1","tenantId":"t1","fileGroupCode":"GROUP1",
         "bizDate":"2026-11-09","requiredFiles":["orders.csv"]}
        """;
    Files.writeString(authority, content);
    String authorityKey = prefix + "t1/auth-2026-11-09.batch.json";
    FilesystemObjectStore changingStore = spy(objectStore);
    AtomicBoolean removed = new AtomicBoolean();
    doAnswer(invocation -> {
          InputStream stream = (InputStream) invocation.callRealMethod();
          if (removed.compareAndSet(false, true)) {
            Files.delete(authority);
          }
          return stream;
        })
        .when(changingStore)
        .get(eq(storage.getBucket()), eq(authorityKey));
    ImportIngressScanner scanner = new ImportIngressScanner(
        fileRecords,
        fileAudits,
        worker,
        properties,
        storage,
        changingStore,
        new ObjectMapper(),
        scanRepository,
        ownershipIdentity);
    declareOwnership(scanner, properties);
    String objectKey = prefix + "t1/orders.csv";

    scanner.scan();
    assertThat(removed).isTrue();
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey))
        .isFalse();
    Files.writeString(authority, content);
    for (int tick = 0;
        tick < 30
            && !fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey);
        tick++) {
      scanner.scan();
    }
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey))
        .isTrue();
  }

  @Test
  @DisplayName("同租户不同目录同名文件各自匹配绑定的业务日清单与文件组")
  void scan_shouldRouteSameNamedFilesToBoundGroups() throws Exception {
    properties.setBatchSize(1);
    properties.setBatchManifestEnabled(true);
    properties.getArrival().setEnabled(true);
    String firstDirectory = prefix + "t1/first/";
    String secondDirectory = prefix + "t1/second/";
    String controlPrefix = "controls/" + UUID.randomUUID() + "/";
    properties.setManifestBindings(List.of(
        manifestBinding(
            "t1", firstDirectory, "GROUP1", controlPrefix + "first-{bizDateCompact}.batch.json"),
        manifestBinding(
            "t1", secondDirectory, "GROUP2", controlPrefix + "second-{bizDate}.batch.json")));
    Path first = root.resolve("batch-test").resolve(firstDirectory);
    Path second = root.resolve("batch-test").resolve(secondDirectory);
    Path control = root.resolve("batch-test").resolve(controlPrefix);
    Files.createDirectories(first);
    Files.createDirectories(second);
    Files.createDirectories(control);
    Files.writeString(first.resolve("orders.csv"), "first");
    Files.writeString(second.resolve("orders.csv"), "second");
    String template = """
        {"schemaVersion":"batch-manifest-v1","tenantId":"t1","fileGroupCode":"%s",
         "bizDate":"2026-11-09","requiredFiles":["orders.csv"]}
        """;
    Files.writeString(control.resolve("first-20261109.batch.json"), template.formatted("GROUP1"));
    Files.writeString(
        control.resolve("second-2026-11-09.batch.json"), template.formatted("GROUP2"));
    String firstKey = firstDirectory + "orders.csv";
    String secondKey = secondDirectory + "orders.csv";
    ImportIngressScanner scanner = scanner();
    for (int tick = 0;
        tick < 50
            && (!fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), firstKey)
                || !fileRecords.existsFileRecordByStoragePath(
                    "t1", storage.getBucket(), secondKey));
        tick++) {
      scanner.scan();
    }
    Map<String, Object> firstRow =
        fileRecords.loadFileRecordByStoragePath("t1", storage.getBucket(), firstKey);
    Map<String, Object> secondRow =
        fileRecords.loadFileRecordByStoragePath("t1", storage.getBucket(), secondKey);
    assertThat(firstRow).isNotEmpty();
    assertThat(secondRow).isNotEmpty();
    assertThat(firstRow.get("metadata_json").toString())
        .contains("GROUP1")
        .doesNotContain("GROUP2");
    assertThat(secondRow.get("metadata_json").toString())
        .contains("GROUP2")
        .doesNotContain("GROUP1");
  }

  @Test
  @DisplayName("扫描根目录的无输入导出束优先使用清单租户并沿用无效业务日回退")
  void manifestOnlyExportAtRoot_shouldUseManifestTenantAndFallbackForInvalidDate()
      throws Exception {
    properties.setBatchSize(10);
    properties.setBatchManifestEnabled(true);
    properties.getArrival().setEnabled(true);
    worker = new ImportWorkerConfiguration(
        null,
        "IMPORT",
        "default-tenant",
        15000L,
        "topic",
        "group",
        null,
        new ImportWorkerConfiguration.FileProcessing(false, 1000, 1000, 500),
        null);
    Path directory = root.resolve("batch-test").resolve(prefix);
    Files.createDirectories(directory);
    String template = """
        {"schemaVersion":"batch-manifest-v2","tenantId":"t1","fileGroupCode":"EXPORTS",
         "bizDate":"%s","workflowCode":"WF_EXPORT",
         "fileMapping":[{"fileName":"output.csv","templateCode":"TPL_EXPORT"}]}
        """;
    Files.writeString(directory.resolve("valid.batch.json"), template.formatted("2026-11-09"));
    Files.writeString(directory.resolve("fallback.batch.json"), template.formatted("invalid"));
    ImportIngressScanner scanner = scanner();
    for (int tick = 0; tick < 8; tick++) {
      scanner.scan();
    }
    Map<String, Object> valid = fileRecords.loadFileRecordByStoragePath(
        "t1", storage.getBucket(), prefix + "valid.batch.json");
    Map<String, Object> fallback = fileRecords.loadFileRecordByStoragePath(
        "t1", storage.getBucket(), prefix + "fallback.batch.json");
    assertThat(valid).isNotEmpty();
    assertThat(fallback).isNotEmpty();
    assertThat(fallback.get("biz_date").toString()).isEqualTo("2026-11-09");
  }

  @Test
  @DisplayName("无输入导出束校验路径租户，无效显式业务日沿用回退并可归档")
  void manifestOnlyExport_shouldPreserveFallbackAndRejectTenantMismatch() throws Exception {
    properties.setBatchSize(10);
    properties.setBatchManifestEnabled(true);
    properties.getArrival().setEnabled(true);
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    String template = """
        {"schemaVersion":"batch-manifest-v2","tenantId":"%s","fileGroupCode":"EXPORTS",
         "bizDate":"%s","workflowCode":"WF_EXPORT",
         "fileMapping":[{"fileName":"output.csv","templateCode":"TPL_EXPORT"}]}
        """;
    Files.writeString(
        directory.resolve("valid.batch.json"), template.formatted("t1", "2026-11-09"));
    Files.writeString(
        directory.resolve("wrong-tenant.batch.json"), template.formatted("other", "2026-11-09"));
    Files.writeString(
        directory.resolve("wrong-date.batch.json"), template.formatted("t1", "invalid"));
    ImportIngressScanner scanner = scanner();
    for (int tick = 0; tick < 8; tick++) {
      scanner.scan();
    }
    String valid = prefix + "t1/valid.batch.json";
    String wrongTenant = prefix + "t1/wrong-tenant.batch.json";
    String wrongDate = prefix + "t1/wrong-date.batch.json";
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), valid))
        .isTrue();
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), wrongTenant))
        .isFalse();
    Map<String, Object> fallback =
        fileRecords.loadFileRecordByStoragePath("t1", storage.getBucket(), wrongDate);
    assertThat(fallback).isNotEmpty();
    assertThat(fallback.get("biz_date").toString()).isEqualTo("2026-11-09");
    Map<String, Object> row =
        fileRecords.loadFileRecordByStoragePath("t1", storage.getBucket(), valid);
    assertThat(row.get("metadata_json").toString()).contains("WF_EXPORT", "TPL_EXPORT");
    assertThat(row.get("file_category")).isEqualTo("EXPORT_TRIGGER");

    String archiveSql = """
        INSERT INTO archive.file_record_archive
        SELECT * FROM batch.file_record WHERE tenant_id = ? AND storage_path = ?
        """;
    assertThat(jdbc.update(archiveSql, "t1", valid)).isEqualTo(1);
    String archivedCategorySql = """
        SELECT file_category FROM archive.file_record_archive WHERE tenant_id = ? AND storage_path = ?
        """;
    assertThat(jdbc.queryForObject(archivedCategorySql, String.class, "t1", valid))
        .isEqualTo("EXPORT_TRIGGER");
    assertThatThrownBy(() -> jdbc.update(
            "UPDATE batch.file_record SET file_category = 'UNKNOWN' WHERE tenant_id = ? AND storage_path = ?",
            "t1",
            valid))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update(
            "UPDATE archive.file_record_archive SET file_category = 'UNKNOWN' WHERE tenant_id = ? AND storage_path = ?",
            "t1",
            valid))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("对象在发现后替换时不登记旧快照，下一轮重新观察后登记新版本")
  void scan_shouldRecheckObjectVersion_beforeRegistration() throws Exception {
    properties.setBatchSize(10);
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Path data = directory.resolve("change.csv");
    Files.writeString(data, "old");
    String objectKey = prefix + "t1/change.csv";
    FilesystemObjectStore changingStore = spy(objectStore);
    AtomicBoolean changed = new AtomicBoolean();
    doAnswer(invocation -> {
          ObjectListing result = (ObjectListing) invocation.callRealMethod();
          if (changed.compareAndSet(false, true)) {
            Files.writeString(data, "newer-and-longer");
          }
          return result;
        })
        .when(changingStore)
        .list(eq(storage.getBucket()), eq(objectKey), isNull(), eq(1));
    ImportIngressScanner scanner = new ImportIngressScanner(
        fileRecords,
        fileAudits,
        worker,
        properties,
        storage,
        changingStore,
        new ObjectMapper(),
        scanRepository,
        ownershipIdentity);
    declareOwnership(scanner, properties);

    scanner.scan();
    assertThat(changed).isTrue();
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey))
        .isFalse();
    for (int i = 0;
        i < 10 && !fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey);
        i++) {
      scanner.scan();
    }
    Map<String, Object> row =
        fileRecords.loadFileRecordByStoragePath("t1", storage.getBucket(), objectKey);
    assertThat(row).isNotEmpty();
    assertThat(((Number) row.get("file_size_bytes")).longValue()).isEqualTo(Files.size(data));
  }

  @Test
  @DisplayName("跨重启的稳定观察在对象版本变化后重新计时")
  void scan_shouldResetPersistedStability_afterObjectChanges() throws Exception {
    properties.setBatchSize(10);
    properties.setStabilityWindowSeconds(60);
    Path directory = root.resolve("batch-test").resolve(prefix).resolve("t1");
    Files.createDirectories(directory);
    Path data = directory.resolve("stable.csv");
    Files.writeString(data, "old");
    ImportIngressScanner original = scanner();
    original.scan();
    String scope = original.scanScope();
    String objectKey = prefix + "t1/stable.csv";
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey))
        .isFalse();
    String ageSql = """
        UPDATE batch.import_ingress_scan_object
           SET first_observed_at = clock_timestamp() - interval '61 seconds'
         WHERE scope_id = ? AND object_key = ?
        """;
    jdbc.update(ageSql, scope, objectKey);
    Files.writeString(data, "a-different-version");

    ImportIngressScanner restarted = scanner();
    restarted.scan();
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey))
        .isFalse();
    jdbc.update(ageSql, scope, objectKey);
    restarted.scan();
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey))
        .isTrue();
  }

  @Test
  @DisplayName("同一存储桶的重叠前缀并发认领时只有一个扫描器获租")
  void scan_shouldSerializeConcurrentOverlappingPrefixes() throws Exception {
    ImportIngressScanner scanner = scanner();
    String storageId = scanner.scanStorageIdentity();
    String firstScope = scanner.scanScope();
    String digest = scanner.scanConfigurationDigest();
    String secondPrefix = prefix + "t1/";
    properties.setPrefix(secondPrefix);
    String secondScope = scanner.scanScope();
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Optional<Lease>> first = executor.submit(() -> {
        start.await();
        return scanRepository.claim(storageId, firstScope, storage.getBucket(), prefix, digest);
      });
      Future<Optional<Lease>> second = executor.submit(() -> {
        start.await();
        return scanRepository.claim(
            storageId, secondScope, storage.getBucket(), secondPrefix, digest);
      });
      start.countDown();
      Optional<Lease> firstResult = first.get();
      Optional<Lease> secondResult = second.get();
      assertThat(List.of(firstResult, secondResult).stream()
              .filter(Optional::isPresent)
              .count())
          .isEqualTo(1);
      firstResult.or(() -> secondResult).ifPresent(scanRepository::release);
    }
  }

  @Test
  @DisplayName("等待实例失活过期后其他前缀可重新获得存储租约")
  void expiredWaitingScope_shouldNotBlockOthers() {
    ImportIngressScanner scanner = scanner();
    String storageId = scanner.scanStorageIdentity();
    String firstScope = scanner.scanScope();
    String digest = scanner.scanConfigurationDigest();
    Lease first = scanRepository
        .claim(storageId, firstScope, storage.getBucket(), prefix, digest)
        .orElseThrow();
    String secondPrefix = prefix + "t1/";
    properties.setPrefix(secondPrefix);
    String secondScope = scanner.scanScope();
    assertThat(
            scanRepository.claim(storageId, secondScope, storage.getBucket(), secondPrefix, digest))
        .isEmpty();
    scanRepository.release(first);
    String expireWaitingSql = """
        UPDATE batch.import_ingress_scan_state
           SET waiting_until = clock_timestamp() - interval '1 second' WHERE scope_id = ?
        """;
    jdbc.update(expireWaitingSql, secondScope);
    Lease recovered = scanRepository
        .claim(storageId, firstScope, storage.getBucket(), prefix, digest)
        .orElseThrow();
    scanRepository.release(recovered);
  }

  @Test
  @DisplayName("两个定时扫描实例竞争同一存储桶时等待前缀可继续推进")
  void scheduledScanners_shouldFairlyAdvanceDifferentPrefixes() throws Exception {
    String firstPrefix = prefix + "t1/first/";
    String secondPrefix = prefix + "t1/second/";
    Path firstDirectory = root.resolve("batch-test").resolve(firstPrefix);
    Path secondDirectory = root.resolve("batch-test").resolve(secondPrefix);
    Files.createDirectories(firstDirectory);
    Files.createDirectories(secondDirectory);
    Files.writeString(firstDirectory.resolve("a.csv"), "first");
    Files.writeString(secondDirectory.resolve("b.csv"), "second");
    ImportScannerProperties firstProperties = scanProperties(firstPrefix);
    ImportScannerProperties secondProperties = scanProperties(secondPrefix);
    FilesystemObjectStore blockingStore = spy(objectStore);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean blocked = new AtomicBoolean();
    doAnswer(invocation -> {
          if (blocked.compareAndSet(false, true)) {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("测试未释放首个扫描实例");
            }
          }
          return invocation.callRealMethod();
        })
        .when(blockingStore)
        .list(eq(storage.getBucket()), eq(firstPrefix), isNull(), eq(1));
    ImportIngressScanner first = new ImportIngressScanner(
        fileRecords,
        fileAudits,
        worker,
        firstProperties,
        storage,
        blockingStore,
        new ObjectMapper(),
        scanRepository,
        ownershipIdentity);
    ImportIngressScanner second = new ImportIngressScanner(
        fileRecords,
        fileAudits,
        worker,
        secondProperties,
        storage,
        objectStore,
        new ObjectMapper(),
        scanRepository,
        ownershipIdentity);
    declareOwnership(first, firstProperties);
    declareOwnership(second, secondProperties);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    ScheduledFuture<?> firstTask = null;
    ScheduledFuture<?> secondTask = null;
    try {
      firstTask = executor.scheduleWithFixedDelay(
          () -> runScheduled(first, failure), 0, 20, TimeUnit.MILLISECONDS);
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      secondTask = executor.scheduleWithFixedDelay(
          () -> runScheduled(second, failure), 0, 20, TimeUnit.MILLISECONDS);
      String waitingSql =
          "SELECT count(*) FROM batch.import_ingress_scan_state WHERE scope_id = ? AND waiting_since IS NOT NULL";
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline
          && jdbc.queryForObject(waitingSql, Integer.class, second.scanScope()) == 0) {
        Thread.sleep(20);
      }
      assertThat(jdbc.queryForObject(waitingSql, Integer.class, second.scanScope()))
          .isEqualTo(1);
      release.countDown();
      String firstKey = firstPrefix + "a.csv";
      String secondKey = secondPrefix + "b.csv";
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline
          && failure.get() == null
          && (!fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), firstKey)
              || !fileRecords.existsFileRecordByStoragePath(
                  "t1", storage.getBucket(), secondKey))) {
        Thread.sleep(20);
      }
      assertThat(failure.get()).isNull();
      assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), firstKey))
          .isTrue();
      assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), secondKey))
          .isTrue();
    } finally {
      release.countDown();
      if (firstTask != null) {
        firstTask.cancel(true);
      }
      if (secondTask != null) {
        secondTask.cancel(true);
      }
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private ImportScannerProperties scanProperties(String scanPrefix) {
    ImportScannerProperties value = new ImportScannerProperties();
    value.setPrefix(scanPrefix);
    value.setBatchSize(1);
    value.setStabilityWindowSeconds(0);
    value.setDefaultBizDate("2026-11-09");
    value.setPollIntervalMillis(20);
    return value;
  }

  private static void runScheduled(
      ImportIngressScanner scanner, AtomicReference<Throwable> failure) {
    try {
      scanner.scheduledScan();
    } catch (Throwable ex) {
      failure.compareAndSet(null, ex);
    }
  }

  @Test
  @DisplayName("租约竞争和过期围栏拒绝旧执行者，回滚文件登记及进度")
  void expiredOwner_shouldBeFencedAndRollBackFileAndProgress() {
    ImportIngressScanner scanner = scanner();
    String scope = scanner.scanScope();
    String storageId = scanner.scanStorageIdentity();
    String digest = scanner.scanConfigurationDigest();
    Lease first = scanRepository
        .claim(storageId, scope, storage.getBucket(), prefix, digest)
        .orElseThrow();
    assertThat(scanRepository.claim(storageId, scope, storage.getBucket(), prefix, digest))
        .isEmpty();
    properties.setPrefix(prefix + "t1/");
    String overlappingScope = scanner.scanScope();
    assertThat(scanner.scanStorageIdentity()).isEqualTo(storageId);
    assertThat(scanRepository.claim(
            storageId, overlappingScope, storage.getBucket(), properties.getPrefix(), digest))
        .isEmpty();
    properties.setPrefix(prefix);
    String expireSql = """
        UPDATE batch.import_ingress_scan_state
           SET lease_until = clock_timestamp() - interval '1 second' WHERE scope_id = ?
        """;
    jdbc.update(expireSql, scope);
    String expireStorageSql = """
        UPDATE batch.import_ingress_scan_storage_lease
           SET lease_until = clock_timestamp() - interval '1 second' WHERE storage_id = ?
        """;
    jdbc.update(expireStorageSql, storageId);
    Lease second = scanRepository
        .claim(storageId, scope, storage.getBucket(), prefix, digest)
        .orElseThrow();
    assertThat(scanRepository.fenced(first, () -> {
          throw new AssertionError("过期执行者不应进入事务");
        }))
        .isFalse();

    String objectKey = prefix + "t1/rollback.csv";
    FileRecordParam file = FileRecordParam.builder()
        .tenantId("t1")
        .fileCategory("INPUT")
        .fileName("rollback.csv")
        .fileFormatType("DELIMITED")
        .storageType("S3")
        .storagePath(objectKey)
        .storageBucket(storage.getBucket())
        .sourceType("SYSTEM")
        .fileStatus("RECEIVED")
        .bizDate(LocalDate.parse("2026-11-09"))
        .build();
    AtomicReference<Long> attemptedFileId = new AtomicReference<>();
    assertThatThrownBy(() -> scanRepository.fenced(second, () -> {
          Long fileId = fileRecords.createFileRecord(file);
          attemptedFileId.set(fileId);
          FileAuditParam audit = FileAuditParam.builder()
              .tenantId("t1")
              .fileId(fileId)
              .operationType("RECEIVE_SCAN")
              .operationResult("SUCCESS")
              .build();
          fileAudits.appendAudit(audit);
          scanRepository.advance(second, "INDEX", null, 0, second.cycle());
          throw new IllegalStateException("验证整体回滚");
        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("验证整体回滚");
    assertThat(fileRecords.existsFileRecordByStoragePath("t1", storage.getBucket(), objectKey))
        .isFalse();
    String auditCountSql = "SELECT count(*) FROM batch.file_audit_log WHERE file_id = ?";
    Long auditCount = jdbc.queryForObject(auditCountSql, Long.class, attemptedFileId.get());
    assertThat(auditCount).isZero();
    String phaseSql = "SELECT phase FROM batch.import_ingress_scan_state WHERE scope_id = ?";
    String phase = jdbc.queryForObject(phaseSql, String.class, scope);
    assertThat(phase).isEqualTo("DISCOVER");
    scanRepository.release(second);
    properties.setSourceType("API");
    String changedDigest = scanner.scanConfigurationDigest();
    assertThatThrownBy(() ->
            scanRepository.claim(storageId, scope, storage.getBucket(), prefix, changedDigest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("扫描规则");
  }

  private ImportIngressScanner scanner() {
    ImportIngressScanner scanner = new ImportIngressScanner(
        fileRecords,
        fileAudits,
        worker,
        properties,
        storage,
        objectStore,
        new ObjectMapper(),
        scanRepository,
        ownershipIdentity);
    declareOwnership(scanner, properties);
    return scanner;
  }

  private void declareOwnership(ImportIngressScanner scanner, ImportScannerProperties config) {
    String scopeSql = """
        INSERT INTO batch.import_ingress_ownership(scope_id, storage_id, bucket_name, object_prefix, state)
        VALUES (?, ?, ?, ?, 'ACTIVE') ON CONFLICT (scope_id) DO NOTHING
        """;
    jdbc.update(
        scopeSql,
        scanner.scanScope(),
        scanner.scanStorageIdentity(),
        storage.getBucket(),
        config.getPrefix());
    String manifestSql = """
        INSERT INTO batch.import_ingress_ownership_binding
          (scope_id, binding_type, tenant_id, object_prefix, file_group_code, manifest_key_template)
        VALUES (?, 'MANIFEST', ?, ?, ?, ?) ON CONFLICT DO NOTHING
        """;
    for (ImportScannerProperties.ManifestBinding binding : config.getManifestBindings()) {
      jdbc.update(
          manifestSql,
          scanner.scanScope(),
          binding.getTenantId(),
          binding.getObjectPrefix(),
          binding.getFileGroupCode(),
          binding.getManifestKeyTemplate());
    }
    String channelSql = """
        INSERT INTO batch.import_ingress_ownership_binding
          (scope_id, binding_type, tenant_id, object_prefix, channel_code)
        VALUES (?, 'CHANNEL', ?, ?, ?) ON CONFLICT DO NOTHING
        """;
    for (ImportScannerProperties.ChannelBinding binding : config.getChannelBindings()) {
      jdbc.update(
          channelSql,
          scanner.scanScope(),
          binding.getTenantId(),
          binding.getObjectPrefix(),
          binding.getChannelCode());
    }
    config.setManifestBindings(List.of());
    config.setChannelBindings(List.of());
  }

  private static ImportScannerProperties.ManifestBinding manifestBinding(
      String tenant, String directory, String group, String keyTemplate) {
    ImportScannerProperties.ManifestBinding binding = new ImportScannerProperties.ManifestBinding();
    binding.setTenantId(tenant);
    binding.setObjectPrefix(directory);
    binding.setFileGroupCode(group);
    binding.setManifestKeyTemplate(keyTemplate);
    return binding;
  }
}
