package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.config.FilesystemStorageProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.storage.FilesystemPresignTokens;
import io.github.pinpols.batch.e2e.apps.E2eExportApplication;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.jdbc.Sql;

/**
 * 端到端测试：对象存储 filesystem 后端（{@code batch.storage.backend=filesystem}）主链路闭环。
 *
 * <p>与 {@link ExportPipelineE2eIT}（S3/MinIO 后端）互补：导出产物应落在测试临时根目录
 * {@code root/<bucket>/<key>}，且抽象接口读取、磁盘直读、presign 令牌三路结果一致。
 *
 * <p>本类只在测试后端为 filesystem 时执行（{@code -Dbatch.test.storage.backend=filesystem}）：
 * 基类属性注册会按该系统属性登记 {@code batch.storage.filesystem.*}。e2e 模块每个 IT 类独立
 * JVM/数据库，后端守卫（Stateful Backend Guard）首启记录 filesystem baseline，无需外部 cutover。
 */
@SpringBootTest(
    classes = E2eExportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.export.worker-type=EXPORT")
@ActiveProfiles({"test", "e2e"})
@Sql(
    scripts = {
      E2eTestSql.BIZ_SCHEMA,
      E2eTestSql.EXPORT_TEMPLATE_SEED,
    })
@Tag("e2e")
@EnabledIfSystemProperty(named = "batch.test.storage.backend", matches = "filesystem")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FilesystemBackendPipelineE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String BATCH_NO = "E2E-SET-FS-1";
  private static final String SETTLEMENT_NO = "E2E-FS-SET-001";

  private final LaunchService launchService;
  private final JdbcTemplate jdbcTemplate;
  private final E2eOutboxPublishSupport e2eOutboxPublishSupport;
  private final BatchObjectStore objectStore;
  private final S3StorageProperties s3Properties;
  private final FilesystemStorageProperties filesystemProperties;
  private final DataSource businessDataSource;

  FilesystemBackendPipelineE2eIT(
      LaunchService launchService,
      JdbcTemplate jdbcTemplate,
      E2eOutboxPublishSupport e2eOutboxPublishSupport,
      BatchObjectStore objectStore,
      S3StorageProperties s3Properties,
      FilesystemStorageProperties filesystemProperties,
      @Qualifier("exportBusinessDataSource") DataSource businessDataSource) {
    this.launchService = launchService;
    this.jdbcTemplate = jdbcTemplate;
    this.e2eOutboxPublishSupport = e2eOutboxPublishSupport;
    this.objectStore = objectStore;
    this.s3Properties = s3Properties;
    this.filesystemProperties = filesystemProperties;
    this.businessDataSource = businessDataSource;
  }

  @Test
  void exportJobRunsOnFilesystemBackendAndArtifactIsReadableFromDiskStoreAndPresign()
      throws Exception {
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    seedSettlementData(businessJdbc);

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "EXPORT", "export", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("batchNo", BATCH_NO);
    params.put("templateCode", "EXP-SETTLEMENT-JSON");
    params.put("bizDate", "2026-01-15");
    params.put("bizType", "SETTLEMENT");
    params.put("fileCode", "e2e-fs-export-file");

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.jobCode(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-tr-fs-export",
        params));

    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String status = jdbcTemplate.queryForObject("""
                      select t.task_status from batch.job_task t
                      join batch.job_instance ji on ji.id = t.job_instance_id
                      where ji.tenant_id = ? and ji.dedup_key = ?
                      """, String.class, TENANT, seed.dedupKey());
          assertThat(status).isEqualTo("SUCCESS");
        });

    Map<String, Object> fileRecord = jdbcTemplate.queryForMap("""
            select fr.storage_path, fr.file_status, fr.file_size_bytes
            from batch.file_record fr
            join batch.job_instance ji on ji.tenant_id = fr.tenant_id
            where ji.tenant_id = ? and ji.dedup_key = ? and fr.file_category = 'OUTPUT'
            order by fr.id desc
            limit 1
            """, TENANT, seed.dedupKey());
    String storagePath = String.valueOf(fileRecord.get("storage_path"));
    assertThat(storagePath).as("export: file_record.storage_path must be set").isNotBlank();

    String bucket = s3Properties.getBucket();
    String key = storagePath.startsWith(bucket + "/")
        ? storagePath.substring(bucket.length() + 1)
        : storagePath;

    // 1) 磁盘直读：产物落在 filesystem 根目录 root/<bucket>/<key>
    Path onDisk = filesystemRoot().resolve(bucket).resolve(key);
    assertThat(Files.isRegularFile(onDisk))
        .as("export: artifact must exist on filesystem root: %s", onDisk)
        .isTrue();
    String diskContent = Files.readString(onDisk, StandardCharsets.UTF_8);
    assertThat(diskContent)
        .as("export: artifact content must contain settlement record")
        .contains(SETTLEMENT_NO);

    // 2) 抽象接口读取：BatchObjectStore.get 与磁盘内容一致
    String storeContent;
    try (InputStream in = objectStore.get(bucket, key)) {
      storeContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(storeContent).isEqualTo(diskContent);

    // 3) presign：FS 后端返回 HMAC 能力令牌 URL，校验签名与过期参数
    String presignUrl = objectStore.presign(bucket, key, Duration.ofMinutes(5));
    Map<String, String> query = parseQuery(presignUrl);
    assertThat(query.get("b")).isEqualTo(bucket);
    assertThat(URLDecoder.decode(query.get("k"), StandardCharsets.UTF_8)).isEqualTo(key);
    long expEpochSec = Long.parseLong(query.get("e"));
    String signature = query.get("s");
    assertThat(FilesystemPresignTokens.verify(
            bucket, key, expEpochSec, signature, filesystemProperties.getPresignSecret()))
        .as("export: fs presign token must verify with configured secret")
        .isTrue();
  }

  private void seedSettlementData(JdbcTemplate businessJdbc) {
    Long batchId = businessJdbc.queryForObject("""
            insert into biz.settlement_batch (
                tenant_id, batch_no, biz_date, accounting_period, batch_status,
                total_record_count, total_amount, currency
            ) values (?, ?, date '2026-01-15', '202601', 'READY', 1, 0, 'CNY')
            returning id
            """, Long.class, TENANT, BATCH_NO);
    assertThat(batchId).isNotNull();

    businessJdbc.update("""
        insert into biz.settlement_detail (
            tenant_id, batch_id, settlement_no, customer_no, biz_date, accounting_period,
            gross_amount, fee_amount, net_amount, currency, settlement_status
        ) values (?, ?, ?, ?, date '2026-01-15', '202601', 10.00, 1.00, 9.00, 'CNY', 'READY')
        """, TENANT, batchId, SETTLEMENT_NO, "C-FS-1");
  }

  private static Map<String, String> parseQuery(String url) {
    int q = url.indexOf('?');
    Map<String, String> params = new LinkedHashMap<>();
    if (q < 0) {
      return params;
    }
    for (String pair : url.substring(q + 1).split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        params.put(pair.substring(0, eq), pair.substring(eq + 1));
      }
    }
    return params;
  }
}
