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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;

/**
 * P1 端到端测试：Export 写对象存储失败 + 重试耗尽 → 死信。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>模拟“对象存储不可用”这一高频生产故障（通过覆盖 MinIO endpoint 指向不可达地址）。
 *   <li>验证 orchestrator 的重试治理生效：有重试预算时会反复重排队；预算耗尽后进入死信（dead_letter_task）。
 * </ul>
 *
 * <p>断言点：
 *
 * <ul>
 *   <li>最终存在与该 job_instance 对应的 {@code batch.dead_letter_task}（source_type=JOB_PARTITION）。
 * </ul>
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
class ExportStorageFailureE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String BATCH_NO = "E2E-EXPORT-MINIO-FAIL-1";

  @DynamicPropertySource
  static void useUnreachableMinio(DynamicPropertyRegistry registry) {
    registry.add("batch.storage.s3.endpoint", () -> "http://127.0.0.1:19987");
  }

  @Autowired private LaunchService launchService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Autowired
  @Qualifier("exportBusinessDataSource")
  private DataSource businessDataSource;

  @Test
  void exportToUnreachableStorageExhaustsRetriesAndDeadLetters() {
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
        "E2E-MF-001",
        "C-MF-1");

    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            new E2eScenarioFixture.LaunchPreparationSpec(
                    jdbcTemplate, TENANT, "EXPORT", "export", TriggerType.API)
                .retryPolicy("FIXED")
                .retryMaxCount(2));

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("batchNo", BATCH_NO);
    params.put("templateCode", "EXP-CUSTOMER-JSON");
    params.put("bizDate", "2026-01-15");
    params.put("bizType", "SETTLEMENT");
    params.put("fileCode", "e2e-export-minio-fail");

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-export-minio-fail",
            params));

    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(240))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(
            () -> {
              e2eOutboxPublishSupport.publishAllPending(TENANT);
              Long dlq =
                  jdbcTemplate.queryForObject(
                      """
                      select count(*) from batch.dead_letter_task dlt
                      join batch.job_partition jp on jp.id = dlt.source_id
                      join batch.job_instance ji on ji.id = jp.job_instance_id
                      where dlt.source_type = 'JOB_PARTITION'
                        and ji.tenant_id = ? and ji.dedup_key = ?
                      """,
                      Long.class,
                      TENANT,
                      seed.dedupKey());
              assertThat(dlq).isNotNull().isGreaterThanOrEqualTo(1L);
            });
  }
}
