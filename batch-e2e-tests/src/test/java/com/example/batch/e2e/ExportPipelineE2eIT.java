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
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.math.BigDecimal;
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
 * 端到端测试：Export 主链路成功闭环。
 *
 * <p>链路路径：
 *
 * <pre>
 * launch → orchestrator 调度/落 outbox → Kafka 派发 → export worker claim → 读取业务明细 → 生成导出产物
 *     → 写平台 file_record/对象存储 → worker report → orchestrator 终态
 * </pre>
 *
 * <p>说明：该用例偏“状态级成功”断言；更严格的产物内容断言见 {@link ExportContentVerificationE2eIT}。
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
@Tag("smoke")
class ExportPipelineE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String BATCH_NO = "E2E-SET-EXPORT-1";

  @Autowired private LaunchService launchService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Autowired
  @Qualifier("exportBusinessDataSource")
  private DataSource businessDataSource;

  @Test
  void exportJobRunsThroughKafkaClaimAndReportsSuccess() {
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    Long batchId =
        businessJdbc.queryForObject(
            """
            insert into biz.settlement_batch (
                tenant_id, batch_no, biz_date, accounting_period, batch_status,
                total_record_count, total_amount, currency
            ) values (?, ?, date '2026-01-15', '202601', 'READY', 1, 0, 'CNY')
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
        ) values (?, ?, ?, ?, date '2026-01-15', '202601', 10.00, 1.00, 9.00, 'CNY', 'READY')
        """,
        TENANT,
        batchId,
        "E2E-SET-001",
        "C-E2E-1");

    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "EXPORT", "export", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("batchNo", BATCH_NO);
    params.put("templateCode", "EXP-SETTLEMENT-JSON");
    params.put("bizDate", "2026-01-15");
    params.put("bizType", "SETTLEMENT");
    params.put("fileCode", "e2e-export-file");

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-export",
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
              assertThat(status).isEqualTo("SUCCESS");
            });

    BigDecimal total =
        businessJdbc.queryForObject(
            "select total_amount from biz.settlement_batch where tenant_id = ? and batch_no = ?",
            BigDecimal.class,
            TENANT,
            BATCH_NO);
    assertThat(total).isNotNull();
  }
}
