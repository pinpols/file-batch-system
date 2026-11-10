package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.config.BatchKmsProperties;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eExportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@E2eSpringBootTest(
    classes = {E2eExportApplication.class, ExportEncryptedArtifactE2eIT.CryptoConfiguration.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.export.worker-type=EXPORT")
@E2eBusinessSchema
@Sql(scripts = E2eTestSql.EXPORT_TEMPLATE_SEED)
@DisplayName("加密导出产物端到端验证")
class ExportEncryptedArtifactE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String KEY_REF = "EXPORT_E2E_TEST_KEY";
  private static final LocalDate BIZ_DATE = LocalDate.now(ZoneOffset.UTC).plusDays(1);

  @TestConfiguration(proxyBeanMethods = false)
  static class CryptoConfiguration {
    @Bean
    @Primary
    BatchObjectCryptoService exportTestCryptoService() {
      BatchSecurityProperties security = new BatchSecurityProperties();
      security.setBypassMode(false);
      BatchKmsProperties kms = new BatchKmsProperties();
      kms.setDefaultKeyRef(KEY_REF);
      String key = Base64.getEncoder()
          .encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.US_ASCII));
      kms.setKeys(Map.of(KEY_REF, key));
      return new BatchObjectCryptoService(security, kms);
    }
  }

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private E2eOutboxPublishSupport outbox;

  @Autowired
  private BatchObjectCryptoService crypto;

  @Autowired
  @Qualifier("exportBusinessDataSource")
  private DataSource businessDataSource;

  @Test
  @DisplayName("Worker 加密导出后，关联文件大小、摘要和解密业务内容与实际对象一致")
  void shouldRegisterActualEncryptedObjectAndRecoverBusinessContent() throws Exception {
    String enableEncryptionSql = """
        update batch.file_template_config
        set content_encryption_enabled = true, encryption_key_ref = ?
        where tenant_id = ? and template_code = 'EXP-SETTLEMENT-JSON'
        """;
    String insertBatchSql = """
        insert into biz.settlement_batch (
          tenant_id, batch_no, biz_date, accounting_period, batch_status,
          total_record_count, total_amount, currency
        ) values (?, ?, ?, ?, 'READY', 2, 300, 'CNY') returning id
        """;
    String insertDetailsSql = """
        insert into biz.settlement_detail (
          tenant_id, batch_id, settlement_no, customer_no, biz_date, accounting_period,
          gross_amount, fee_amount, net_amount, currency, settlement_status
        ) values
          (?, ?, ?, 'C-ENC-1', ?, ?, 100, 5, 95, 'CNY', 'READY'),
          (?, ?, ?, 'C-ENC-2', ?, ?, 200, 10, 190, 'CNY', 'READY')
        """;
    String selectTaskSql = """
        select task.task_status from batch.job_task task
        join batch.job_instance instance on instance.id = task.job_instance_id
        where instance.tenant_id = ? and instance.dedup_key = ?
        """;
    String selectFileSql = """
        select file.id, file.file_size_bytes, file.checksum_value, file.storage_bucket,
               file.storage_path, file.metadata_json::text as metadata
        from batch.job_instance instance
        join batch.pipeline_instance pipeline on pipeline.tenant_id = instance.tenant_id
          and pipeline.related_job_instance_id = instance.id
        join batch.file_record file on file.tenant_id = pipeline.tenant_id and file.id = pipeline.file_id
        where instance.tenant_id = ? and instance.dedup_key = ? and file.file_category = 'OUTPUT'
        """;
    String suffix = Long.toUnsignedString(System.nanoTime());
    String batchNo = "ENC-" + suffix;
    String firstDetail = "ENC-A-" + suffix;
    String secondDetail = "ENC-B-" + suffix;
    String period = BIZ_DATE.format(DateTimeFormatter.ofPattern("yyyyMM"));
    assertThat(jdbcTemplate.update(enableEncryptionSql, KEY_REF, TENANT)).isEqualTo(1);
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    Long batchId =
        businessJdbc.queryForObject(insertBatchSql, Long.class, TENANT, batchNo, BIZ_DATE, period);
    businessJdbc.update(
        insertDetailsSql,
        TENANT,
        batchId,
        firstDetail,
        BIZ_DATE,
        period,
        TENANT,
        batchId,
        secondDetail,
        BIZ_DATE,
        period);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "EXPORT", "export", TriggerType.API, BIZ_DATE);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("batchNo", batchNo);
    params.put("templateCode", "EXP-SETTLEMENT-JSON");
    params.put("bizDate", BIZ_DATE.toString());
    params.put("bizType", "SETTLEMENT");
    params.put("fileCode", "e2e-encrypted-" + suffix);
    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        BIZ_DATE,
        TriggerType.API,
        seed.requestId(),
        "e2e-encrypted-" + suffix,
        params));
    outbox.publishAllPending(TENANT);
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String status =
              jdbcTemplate.queryForObject(selectTaskSql, String.class, TENANT, seed.dedupKey());
          assertThat(status).isEqualTo("SUCCESS");
        });
    Map<String, Object> file = jdbcTemplate.queryForMap(selectFileSql, TENANT, seed.dedupKey());
    String bucket = (String) file.get("storage_bucket");
    String path = (String) file.get("storage_path");
    byte[] ciphertext = readObject(bucket, path);
    assertThat(new String(ciphertext, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("BATCHENC");
    assertThat(((Number) file.get("file_size_bytes")).longValue()).isEqualTo(ciphertext.length);
    String sha256 =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ciphertext));
    assertThat(file.get("checksum_value")).isEqualTo(sha256);
    assertThat(String.valueOf(file.get("metadata"))).contains(KEY_REF);
    String plaintext = new String(crypto.decrypt(ciphertext), StandardCharsets.UTF_8);
    JsonNode details = JsonUtils.fromJson(plaintext, JsonNode.class).path("details");
    assertThat(details.isArray()).isTrue();
    assertThat(details.size()).isEqualTo(2);
    assertThat(details.toString()).contains(firstDetail, secondDetail, "C-ENC-1", "C-ENC-2");
  }
}
