package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.e2e.apps.E2eExportApplication;
import com.example.batch.e2e.support.E2eOutboxPublishSupport;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import com.example.batch.e2e.support.E2eTestSql;
import com.example.batch.e2e.support.verifier.ExportFileVerifier;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * P1 端到端测试：Export 成功链路的“内容级”验收。
 *
 * <p>为什么要做内容级断言：仅断言 task_status=SUCCESS 可能掩盖“产物为空/字段错/汇总没更新”等交付缺陷。 因此本用例在主链路成功基础上，进一步校验导出产物与业务汇总字段。
 *
 * <p>本用例在 {@link ExportPipelineE2eIT} 的基础上增强断言：
 *
 * <ul>
 *   <li>导出文件在 MinIO 上可读取且非空（best-effort：如果存储后端不是 MinIO，则退化为 storage_path 非空）
 *   <li>导出内容包含关键业务字段（如 settlement_no）
 *   <li>业务批次记录仍可查询，导出不会破坏结算批次主数据
 * </ul>
 *
 * <p>说明：MinIO 读取使用 {@link AbstractIntegrationTest#minioEndpoint()} / {@link
 * AbstractIntegrationTest#minioBucket()}。 若未来导出存储路径或桶策略变更，测试允许退化为“平台表记录了
 * storage_path”这一弱内容信号，避免把环境差异当成业务失败。
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
class ExportContentVerificationE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String BATCH_NO = "E2E-CONTENT-VERIFY-1";

  @Autowired private LaunchService launchService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Autowired
  @Qualifier("exportBusinessDataSource")
  private DataSource businessDataSource;

  /**
   * Content-level export verification:
   *
   * <ol>
   *   <li>Seed a settlement batch + 2 detail records
   *   <li>Run export pipeline to SUCCESS
   *   <li>Assert the settlement batch row remains queryable after export
   *   <li>Assert file_record has a storage_path set (file was produced)
   *   <li>Attempt to download the file from MinIO and assert it is non-empty with lines > 0
   * </ol>
   */
  @Test
  void exportJobProducesNonEmptyFileAndUpdatesSettlementAmount() throws Exception {
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);

    Long batchId =
        businessJdbc.queryForObject(
            """
            insert into biz.settlement_batch (
                tenant_id, batch_no, biz_date, accounting_period, batch_status,
                total_record_count, total_amount, currency
            ) values (?, ?, date '2026-01-15', '202601', 'READY', 2, 0, 'CNY')
            returning id
            """,
            Long.class,
            TENANT,
            BATCH_NO);
    assertThat(batchId).isNotNull();

    businessJdbc.update(
        """
        insert into biz.settlement_detail (
            tenant_id, batch_id, settlement_no, customer_no, biz_date, accounting_period,
            gross_amount, fee_amount, net_amount, currency, settlement_status
        ) values
            (?, ?, 'E2E-CV-001', 'C-CV-1', date '2026-01-15', '202601', 100.00, 5.00, 95.00, 'CNY', 'READY'),
            (?, ?, 'E2E-CV-002', 'C-CV-2', date '2026-01-15', '202601', 200.00, 10.00, 190.00, 'CNY', 'READY')
        """,
        TENANT,
        batchId,
        TENANT,
        batchId);

    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "EXPORT", "export", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("batchNo", BATCH_NO);
    params.put("templateCode", "EXP-SETTLEMENT-JSON");
    params.put("bizDate", "2026-01-15");
    params.put("bizType", "SETTLEMENT");
    params.put("fileCode", "e2e-cv-export-file");

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-cv-export",
            params));

    e2eOutboxPublishSupport.publishAllPending(TENANT);

    // Wait for task SUCCESS
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
              assertThat(status).isEqualTo("SUCCESS");
            });

    // Content-level triple-check (状态 + 产物 + 审计) via ExportFileVerifier
    ExportFileVerifier.forTenant(TENANT)
        .dedupKey(seed.dedupKey())
        .platformJdbc(jdbcTemplate)
        .businessJdbc(businessJdbc)
        .batchNo(BATCH_NO)
        .expectedMinFileRows(1)
        .expectedContentSnippets("E2E-CV-001", "E2E-CV-002")
        .minioEndpoint(minioEndpoint())
        .minioBucket(minioBucket())
        .build()
        .verify();
  }
}
