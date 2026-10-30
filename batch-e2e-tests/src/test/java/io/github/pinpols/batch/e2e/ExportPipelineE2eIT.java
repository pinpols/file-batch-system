package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.dto.FrozenFileTemplateSnapshot;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eExportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
@E2eSpringBootTest(
    classes = E2eExportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.export.worker-type=EXPORT")
@E2eBusinessSchema
@Sql(
    scripts = {
      E2eTestSql.EXPORT_TEMPLATE_SEED,
    })
@Tag("smoke")
@DisplayName("端到端测试: 导出主链路从触发受理, 事件派发, 任务领取到业务明细读取与导出产物落库的成功闭环, 验证任务成功终态, 业务汇总可读与导出文件记录一致")
class ExportPipelineE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String BATCH_NO = "E2E-SET-EXPORT-1";

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  private final SecretPayloadProtector secretPayloadProtector;

  @Autowired
  @Qualifier("exportBusinessDataSource")
  private DataSource businessDataSource;

  @Autowired
  ExportPipelineE2eIT(SecretPayloadProtector secretPayloadProtector) {
    this.secretPayloadProtector = secretPayloadProtector;
  }

  @Test
  @DisplayName("单个导出任务经事件派发与任务领取后收敛为成功终态, 业务侧结算批次的汇总金额可读且非空")
  void shouldReportSuccess_whenExportTaskClaimedAndArtifactGenerated() {
    final String selectTotalAmountSql = """
        select total_amount from biz.settlement_batch where tenant_id = ? and batch_no = ?
    """;

    final String selectTaskStatusSql = """
                      select t.task_status from batch.job_task t
                      join batch.job_instance ji on ji.id = t.job_instance_id
                      where ji.tenant_id = ? and ji.dedup_key = ?
                      """;

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    seedSettlementData(businessJdbc, BATCH_NO, "E2E-SET-001", "C-E2E-1");

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "EXPORT", "export", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("batchNo", BATCH_NO);
    params.put("templateCode", "EXP-SETTLEMENT-JSON");
    params.put("bizDate", "2026-01-15");
    params.put("bizType", "SETTLEMENT");
    params.put("fileCode", "e2e-export-file");

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-tr-export",
        params));

    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String status = jdbcTemplate.queryForObject(
              selectTaskStatusSql, String.class, TENANT, seed.dedupKey());
          assertThat(status).isEqualTo("SUCCESS");
        });

    BigDecimal total =
        businessJdbc.queryForObject(selectTotalAmountSql, BigDecimal.class, TENANT, BATCH_NO);
    assertThat(total).isNotNull();
  }

  @Test
  @DisplayName("SQL 模板导出演练明确拒绝执行计划,且不写文件记录或业务数据")
  void shouldRejectSqlTemplateExport_whenDryRun() {
    final String selectTotalAmountSql = """
        select total_amount from biz.settlement_batch where tenant_id = ? and batch_no = ?
    """;

    final String selectRunStatusSql = """
              select wr.run_status, ji.instance_status
              from batch.trigger_request tr
              join batch.workflow_run wr on wr.tenant_id = tr.tenant_id
                  and wr.trigger_request_id = tr.id
              join batch.workflow_node_run node_run on node_run.workflow_run_id = wr.id
                  and node_run.node_type = 'JOB'
              join batch.job_instance ji on ji.tenant_id = wr.tenant_id
                  and ji.id = node_run.job_instance_id
              where tr.tenant_id = ? and tr.request_id = ?
              """;
    final String selectTaskErrorSql = """
                  select task.error_code from batch.job_task task
                  join batch.job_instance ji on ji.tenant_id = task.tenant_id
                      and ji.id = task.job_instance_id
                  where ji.tenant_id = ? and ji.dedup_key = ? and task.task_status = 'FAILED'
                  """;
    final String selectCountSql2 = """
        select count(*)::int from batch.file_record where tenant_id = ? and source_ref = ?
    """;

    String batchNo = "E2E-DRY-EXPORT-" + System.nanoTime();
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    seedSettlementData(
        businessJdbc,
        batchNo,
        "E2E-DRY-SET-" + System.nanoTime(),
        "C-E2E-DRY-" + System.nanoTime());

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        new E2eScenarioFixture.LaunchPreparationSpec(
                jdbcTemplate, TENANT, "EXPORT", "export", TriggerType.API)
            .dryRun(true));
    String requestId = seed.requestId();
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("batchNo", batchNo);
    params.put("templateCode", "EXP-SETTLEMENT-JSON");
    params.put("bizDate", "2026-01-15");
    params.put("bizType", "SETTLEMENT");
    params.put("fileCode", "e2e-dry-export-file");
    launchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(LocalDate.of(2026, 1, 15))
        .triggerType(TriggerType.API)
        .requestId(requestId)
        .traceId("e2e-tr-export-dry-run")
        .params(params)
        .dryRun(true)
        .build());
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Map<String, Object> run = jdbcTemplate.queryForMap(selectRunStatusSql, TENANT, requestId);
          assertThat(run)
              .containsEntry("run_status", "FAILED_DRY_RUN")
              .containsEntry("instance_status", "FAILED_DRY_RUN");
          assertThat(jdbcTemplate.queryForList(
                  selectTaskErrorSql, String.class, TENANT, seed.dedupKey()))
              .containsExactly("EXPORT_DRY_RUN_SQL_UNAVAILABLE");
        });

    Integer selectedCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, batchNo);
    assertThat(selectedCountRead2).isZero();
    BigDecimal selectedTotalAmount =
        businessJdbc.queryForObject(selectTotalAmountSql, BigDecimal.class, TENANT, batchNo);
    assertThat(selectedTotalAmount).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("含两个导出模板的批量导出任务展开为两个子任务并全部成功, 落库两条来源一致的已生成文件记录且分别对应两个模板")
  void shouldGenerateAllTemplates_whenBundleExportTaskExpandsIntoSubtasks() {
    final String selectTemplatePayloadsSql = """
        select t.task_payload::text
        from batch.job_task t
        join batch.job_instance ji on ji.tenant_id=t.tenant_id and ji.id=t.job_instance_id
        where ji.tenant_id=? and ji.dedup_key=?
        order by t.id
        """;
    final String selectCountSql = """
                      select count(*)::int from batch.job_task t
                      join batch.job_instance ji on ji.id = t.job_instance_id
                      where ji.tenant_id = ? and ji.dedup_key = ? and t.task_status = 'SUCCESS'
                      """;
    final String selectCountSql2 = """
            select count(*)::int from batch.file_record
            where tenant_id = ? and source_ref = ? and file_status = 'GENERATED'
              and metadata_json->>'templateCode' in ('EXP-SETTLEMENT-JSON','EXP-SETTLEMENT-CSV')
            """;

    String batchNo = "E2E-BUNDLE-EXPORT-" + System.nanoTime();
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    seedSettlementData(businessJdbc, batchNo, "E2E-BND-SET-001", "C-E2E-BND-1");

    LaunchSeed seed = E2eScenarioFixture.prepareBundleLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "BUNDLE_EXPORT", "export");

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("batchNo", batchNo);
    params.put("bizDate", "2026-01-15");
    params.put("bizType", "SETTLEMENT");
    params.put("templateCode", "ROOT_TEMPLATE_MUST_NOT_OVERRIDE_PARTITIONS");
    params.put(
        "bundleFiles",
        List.of(
            Map.of("templateCode", "EXP-SETTLEMENT-JSON"),
            Map.of("templateCode", "EXP-SETTLEMENT-CSV")));

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.EVENT,
        seed.requestId(),
        "e2e-tr-bundle-export",
        params));

    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(() -> {
          Integer successfulTasks =
              jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, seed.dedupKey());
          assertThat(successfulTasks).isEqualTo(2);
        });

    Integer generatedFiles =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, batchNo);
    assertThat(generatedFiles).isEqualTo(2);

    List<String> taskPayloads =
        jdbcTemplate.queryForList(selectTemplatePayloadsSql, String.class, TENANT, seed.dedupKey());
    assertThat(taskPayloads).hasSize(2);
    Map<String, String> templateFormats = new LinkedHashMap<>();
    for (String taskPayloadJson : taskPayloads) {
      Map<String, Object> taskPayload =
          JsonUtils.fromJson(taskPayloadJson, new TypeReference<Map<String, Object>>() {});
      assertThat(taskPayload).doesNotContainKey("_frozenFileTemplatesProtected");
      Object protectedValue = taskPayload.get("frozenFileTemplateProtected");
      assertThat(protectedValue).isInstanceOf(String.class);
      String templateJson = secretPayloadProtector.revealEncrypted((String) protectedValue);
      FrozenFileTemplateSnapshot snapshot =
          JsonUtils.fromJsonStrict(templateJson, FrozenFileTemplateSnapshot.class);
      assertThat(snapshot.tenantId()).isEqualTo(TENANT);
      assertThat(snapshot.templateType()).isEqualTo("EXPORT");
      assertThat(taskPayload).containsEntry("templateCode", snapshot.templateCode());
      templateFormats.put(
          snapshot.templateCode(), (String) snapshot.configuration().get("file_format_type"));
    }
    assertThat(templateFormats)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("EXP-SETTLEMENT-JSON", "JSON", "EXP-SETTLEMENT-CSV", "DELIMITED"));
  }

  private void seedSettlementData(
      JdbcTemplate businessJdbc, String batchNo, String settlementNo, String customerNo) {
    final String insertSettlementBatchSql = """
            insert into biz.settlement_batch (
                tenant_id, batch_no, biz_date, accounting_period, batch_status,
                total_record_count, total_amount, currency
            ) values (?, ?, date '2026-01-15', '202601', 'READY', 1, 0, 'CNY')
            returning id
            """;
    final String insertSettlementDetailSql = """
        insert into biz.settlement_detail (
            tenant_id, batch_id, settlement_no, customer_no, biz_date, accounting_period,
            gross_amount, fee_amount, net_amount, currency, settlement_status
        ) values (?, ?, ?, ?, date '2026-01-15', '202601', 10.00, 1.00, 9.00, 'CNY', 'READY')
        """;

    Long batchId =
        businessJdbc.queryForObject(insertSettlementBatchSql, Long.class, TENANT, batchNo);
    assertThat(batchId).isNotNull();

    businessJdbc.update(insertSettlementDetailSql, TENANT, batchId, settlementNo, customerNo);
  }
}
