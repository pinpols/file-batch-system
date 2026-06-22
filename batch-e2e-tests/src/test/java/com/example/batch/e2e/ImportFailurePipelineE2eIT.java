package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.e2e.apps.E2eImportApplication;
import com.example.batch.e2e.support.E2eOutboxPublishSupport;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import com.example.batch.e2e.support.E2eTestSql;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * 端到端测试：Import 解析失败（不可解析 JSON）场景。
 *
 * <p>测试意图：验证 worker 在 PARSE 阶段捕获解析异常并回报失败，orchestrator 侧把 task/job_instance 落到失败态。
 *
 * <p>关键点：
 *
 * <ul>
 *   <li>输入是“不是 JSON 的字符串”，用于稳定触发 ParseStep 的失败分支。
 *   <li>此用例不启用重试（retry_policy=NONE），目的是验证“失败直接收敛为终态”的最短路径。
 * </ul>
 */
@SpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.import.worker-type=IMPORT")
@ActiveProfiles({"test", "e2e"})
@Sql(
    scripts = {
      E2eTestSql.BIZ_SCHEMA,
      E2eTestSql.IMPORT_TEMPLATE_SEED,
    })
@Tag("e2e")
@Tag("critical")
class ImportFailurePipelineE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired private LaunchService launchService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Test
  void importJobReportsFailedWhenContentIsUnparseable() {
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "JSON");
    params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
    params.put("bizType", "CUSTOMER");
    // Intentionally invalid JSON — ParseStep will throw and return IMPORT_PARSE_FAILED
    params.put("content", "THIS_IS_NOT_VALID_JSON_CONTENT");

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-import-fail",
            params));

    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              String status =
                  jdbcTemplate.queryForObject(
                      """
                      select t.task_status from batch.job_task t
                      join batch.job_instance ji on ji.id = t.job_instance_id
                      where ji.tenant_id = ? and ji.dedup_key = ?
                      """,
                      String.class,
                      TENANT,
                      seed.dedupKey());
              assertThat(status).isEqualTo("FAILED");
            });

    // Verify job_instance also reflects the failure
    String instanceStatus =
        jdbcTemplate.queryForObject(
            "select instance_status from batch.job_instance where tenant_id = ? and dedup_key = ?",
            String.class,
            TENANT,
            seed.dedupKey());
    assertThat(instanceStatus).isIn("FAILED", "PARTIAL_FAILED");
  }

  @Test
  void importJobReportsFailedWhenTrailerControlTotalsMismatch() {
    String templateCode = "IMP-CONTROL-TOTAL-E2E-" + System.nanoTime();
    insertControlTotalTemplate(templateCode);

    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "DELIMITED");
    params.put("templateCode", templateCode);
    params.put("bizType", "CUSTOMER");
    params.put(
        "content",
        "customerNo,customerName,customerType,creditLimit,currencyCode,email,phone,status,openDate,remark\n"
            + "CTL-1,A,PERSONAL,100.00,CNY,a@example.test,1381,ACTIVE,2026-01-15,ok\n"
            + "CTL-2,B,PERSONAL,200.00,CNY,b@example.test,1382,ACTIVE,2026-01-15,ok\n"
            + "T,3,999.00\n");

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-import-control-total",
            params));

    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              Map<String, Object> row =
                  jdbcTemplate.queryForMap(
                      """
                      select t.task_status, t.error_code, ji.instance_status
                      from batch.job_task t
                      join batch.job_instance ji on ji.id = t.job_instance_id
                      where ji.tenant_id = ? and ji.dedup_key = ?
                      """,
                      TENANT,
                      seed.dedupKey());
              assertThat(row.get("task_status")).isEqualTo("FAILED");
              assertThat(row.get("instance_status")).isIn("FAILED", "PARTIAL_FAILED");
              assertThat(String.valueOf(row.get("error_code")))
                  .containsAnyOf("IMPORT_VALIDATE_CONTROL_RECORD", "IMPORT_VALIDATE_CONTROL_TOTAL");
            });
  }

  private void insertControlTotalTemplate(String templateCode) {
    jdbcTemplate.update(
        """
        insert into batch.file_template_config (
          tenant_id, template_code, template_name, template_type, biz_type,
          file_format_type, charset, target_charset, with_bom,
          delimiter, quote_char, escape_char, record_length, header_rows, footer_rows,
          trailer_template, checksum_type, compress_type, encrypt_type,
          field_mappings, validation_rule_set, query_param_schema,
          streaming_enabled, page_size, fetch_size, chunk_size,
          content_encryption_enabled, encryption_key_ref,
          preview_masking_enabled, download_requires_approval,
          enabled, version, created_by, load_target_ref
        )
        select
          tenant_id, ?, ?, template_type, biz_type,
          file_format_type, charset, target_charset, with_bom,
          delimiter, quote_char, escape_char, record_length, header_rows, footer_rows,
          cast(? as jsonb), 'NONE', compress_type, encrypt_type,
          field_mappings, cast(? as jsonb), query_param_schema,
          streaming_enabled, page_size, fetch_size, chunk_size,
          content_encryption_enabled, encryption_key_ref,
          preview_masking_enabled, download_requires_approval,
          enabled, version, created_by, load_target_ref
        from batch.file_template_config
        where tenant_id = ? and template_code = 'IMP-CUSTOMER-CSV'
        order by version desc
        limit 1
        """,
        templateCode,
        "Control total e2e import",
        "{\"present\":true,\"delimiter\":\",\",\"recordType\":\"T\",\"recordTypeIndex\":0,"
            + "\"recordCountIndex\":1,\"controlTotalIndex\":2}",
        "{\"controlRecordCheck\":{\"enabled\":true,\"blocker\":true},"
            + "\"controlTotalCheck\":{\"amountField\":\"creditLimit\",\"blocker\":true}}",
        TENANT);
  }
}
