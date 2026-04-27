package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.e2e.apps.E2eProcessApplication;
import com.example.batch.e2e.support.E2eOutboxPublishSupport;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import com.example.batch.e2e.support.E2eStatusLogger;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessStage;
import com.example.batch.worker.processes.domain.ProcessStageResult;
import com.example.batch.worker.processes.stage.ProcessComputePlugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 端到端测试:PROCESS 主链路 WAP+bookends 完整闭环。
 *
 * <p>覆盖场景:
 *
 * <ul>
 *   <li>{@link #wap_sqlTransform_publishesTargetAndCleansStaging()} —— sqlTransformCompute 配置驱动的完整
 *       WAP 路径:Kafka 派发 → CLAIM → PREPARE 校验 spec → COMPUTE 写 staging → VALIDATE 默认规则通过 → COMMIT
 *       原子写入 target → FEEDBACK 清空 staging + 上报水位
 *   <li>{@link #wap_sqlTransform_validationFailureAbortsCommit()} —— 用户配置的 validations 失败时,COMMIT
 *       不执行,target 保持不变,staging 保留供 forensics
 *   <li>{@link #wap_customPlugin_simpleComputeOnly_runsAll5StagesAsNoOpForOthers()} —— 自定义插件只重写
 *       compute(),框架仍跑全 5 stage 但 prepare/validate/commit/feedback 走默认 no-op
 * </ul>
 */
@SpringBootTest(
    classes = E2eProcessApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.process.worker-type=PROCESS")
@ActiveProfiles({"test", "e2e"})
@Import(ProcessPipelineE2eIT.ProcessE2eTestConfiguration.class)
@Tag("e2e")
class ProcessPipelineE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String CUSTOM_PLUGIN_CODE = "e2eProcessCompute";
  private static final String CUSTOM_PLUGIN_WATERMARK = "wm-custom-plugin-2026-01-15";

  @Autowired private LaunchService launchService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void wap_sqlTransform_publishesTargetAndCleansStaging() throws Exception {
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "PROCESS", "process", TriggerType.API);
    seedSqlTransformBusinessTables();
    seedSqlTransformPipelineDefinition(seed.jobCode(), List.of());

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("bizDate", "2026-01-15");

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-process-wap",
            params));
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              E2eStatusLogger.logJobFlowSnapshot(
                  jdbcTemplate, TENANT, seed.dedupKey(), "ProcessE2e-wap-happy");
              Map<String, Object> outcome = loadProcessOutcome(seed.dedupKey());
              assertThat(outcome.get("task_status")).isEqualTo("SUCCESS");
              assertThat(outcome.get("instance_status")).isEqualTo("SUCCESS");
              assertThat(outcome.get("high_water_mark_out")).isEqualTo("3");
            });

    // Target 表写入正确
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*)::int from biz.process_account_summary where biz_date = date"
                    + " '2026-01-15'",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "select total_amount::text from biz.process_account_summary where account_id='A'"
                    + " and biz_date = date '2026-01-15'",
                String.class))
        .isEqualTo("30.00");

    // staging 在 FEEDBACK 已清空
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*)::int from batch.process_staging", Integer.class))
        .isZero();

    // 5 个 pipeline_step_run 全部 SUCCESS
    Long pipelineInstanceId = loadPipelineInstanceId(seed.dedupKey());
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*)::int from batch.pipeline_step_run where pipeline_instance_id=?",
                Integer.class,
                pipelineInstanceId))
        .isEqualTo(5);
    // COMPUTE 步的 output_summary 含 stagedCount=2
    String stagedCount =
        jdbcTemplate.queryForObject(
            """
            select output_summary ->> 'stagedCount'
            from batch.pipeline_step_run
            where pipeline_instance_id = ? and stage_code = 'COMPUTE'
            """,
            String.class,
            pipelineInstanceId);
    assertThat(stagedCount).isEqualTo("2");
    // COMMIT 步的 output_summary 含 publishedCount=2
    String publishedCount =
        jdbcTemplate.queryForObject(
            """
            select output_summary ->> 'publishedCount'
            from batch.pipeline_step_run
            where pipeline_instance_id = ? and stage_code = 'COMMIT'
            """,
            String.class,
            pipelineInstanceId);
    assertThat(publishedCount).isEqualTo("2");
  }

  @Test
  void wap_sqlTransform_validationFailureAbortsCommit() throws Exception {
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "PROCESS", "process", TriggerType.API);
    seedSqlTransformBusinessTables();
    // 用户校验规则:要求所有行 total_amount >= 1000(本批数据是 30 / 7,所以会失败)
    Map<String, Object> validationRule =
        Map.of(
            "name",
            "min_total_threshold",
            "checkSql",
            "select bool_and((payload->>'total_amount')::numeric >= 1000) AS pass,"
                + " 'total_amount must be >= 1000' AS message"
                + " from batch.process_staging where batch_key = :batchKey");
    seedSqlTransformPipelineDefinition(seed.jobCode(), List.of(validationRule));

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("bizDate", "2026-01-15");

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-process-validate-fail",
            params));
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              E2eStatusLogger.logJobFlowSnapshot(
                  jdbcTemplate, TENANT, seed.dedupKey(), "ProcessE2e-validate-fail");
              Map<String, Object> outcome = loadProcessOutcome(seed.dedupKey());
              assertThat(outcome.get("task_status")).isEqualTo("FAILED");
              assertThat(outcome.get("instance_status")).isEqualTo("FAILED");
            });

    // Target 表保持空(COMMIT 没跑)
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*)::int from biz.process_account_summary", Integer.class))
        .isZero();
    // staging 仍含本批的 2 行(留 forensics,直到下次 prepare 才清)
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*)::int from batch.process_staging where tenant_id=?",
                Integer.class,
                TENANT))
        .isEqualTo(2);
  }

  @Test
  void wap_customPlugin_simpleComputeOnly_runsAll5StagesAsNoOpForOthers() {
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "PROCESS", "process", TriggerType.API);
    seedCustomPluginPipelineDefinition(seed.jobCode());

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("bizDate", "2026-01-15");
    params.put("bizType", "SETTLEMENT_SUMMARY");

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            LocalDate.of(2026, 1, 15),
            TriggerType.API,
            seed.requestId(),
            "e2e-tr-process-custom",
            params));
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              E2eStatusLogger.logJobFlowSnapshot(
                  jdbcTemplate, TENANT, seed.dedupKey(), "ProcessE2e-custom-plugin");
              Map<String, Object> outcome = loadProcessOutcome(seed.dedupKey());
              assertThat(outcome.get("task_status")).isEqualTo("SUCCESS");
              assertThat(outcome.get("instance_status")).isEqualTo("SUCCESS");
              assertThat(outcome.get("high_water_mark_out")).isEqualTo(CUSTOM_PLUGIN_WATERMARK);
            });

    Long pipelineInstanceId = loadPipelineInstanceId(seed.dedupKey());
    // 5 stage 全部跑完
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*)::int from batch.pipeline_step_run where pipeline_instance_id=?",
                Integer.class,
                pipelineInstanceId))
        .isEqualTo(5);
    // 自定义 plugin 只 compute 写 processedCount,其他 stage 走默认 no-op success(无
    // stagedCount/publishedCount)
    String processedCount =
        jdbcTemplate.queryForObject(
            """
            select output_summary ->> 'processedCount'
            from batch.pipeline_step_run
            where pipeline_instance_id = ? and stage_code = 'COMPUTE'
            """,
            String.class,
            pipelineInstanceId);
    assertThat(processedCount).isEqualTo("1");
    // staging 完全没用,保持空
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*)::int from batch.process_staging", Integer.class))
        .isZero();
  }

  // ─── 辅助:种子表与 pipeline 定义 ──────────────────────────────────────────────

  private void seedSqlTransformBusinessTables() {
    jdbcTemplate.execute("create schema if not exists biz");
    jdbcTemplate.execute("drop table if exists biz.process_order_event");
    jdbcTemplate.execute("drop table if exists biz.process_account_summary");
    jdbcTemplate.execute(
        """
        create table biz.process_order_event (
          tenant_id varchar(32) not null,
          account_id varchar(32) not null,
          biz_date date not null,
          event_id bigint not null,
          amount numeric(18, 2) not null
        )
        """);
    jdbcTemplate.execute(
        """
        create table biz.process_account_summary (
          tenant_id varchar(32) not null,
          account_id varchar(32) not null,
          biz_date date not null,
          total_amount numeric(18, 2) not null,
          high_water_mark bigint not null,
          primary key (tenant_id, account_id, biz_date)
        )
        """);
    jdbcTemplate.update(
        """
        insert into biz.process_order_event values
          (?, 'A', date '2026-01-15', 1, 10.00),
          (?, 'A', date '2026-01-15', 2, 20.00),
          (?, 'B', date '2026-01-15', 3, 7.00)
        """,
        TENANT,
        TENANT,
        TENANT);
    // 清掉 staging 残留(可能跨测试方法泄漏)
    jdbcTemplate.execute("delete from batch.process_staging");
  }

  private void seedSqlTransformPipelineDefinition(
      String jobCode, List<Map<String, Object>> validations) throws Exception {
    Long pipelineDefinitionId = insertPipelineDefinition(jobCode, "e2e wap sql transform pipeline");
    Map<String, Object> spec = sqlTransformSpec();
    if (!validations.isEmpty()) {
      spec.put("validations", validations);
    }
    Map<String, Object> stepParams = Map.of("sqlTransformCompute", spec);

    insertProcessSteps(
        pipelineDefinitionId,
        List.of(
            new ProcessStepSeed("PROCESS_PREPARE", "PREPARE", "PROCESS_PREPARE", "{}"),
            new ProcessStepSeed(
                "PROCESS_COMPUTE",
                "COMPUTE",
                "sqlTransformCompute",
                objectMapper.writeValueAsString(stepParams)),
            new ProcessStepSeed("PROCESS_VALIDATE", "VALIDATE", "PROCESS_VALIDATE", "{}"),
            new ProcessStepSeed("PROCESS_COMMIT", "COMMIT", "PROCESS_COMMIT", "{}"),
            new ProcessStepSeed("PROCESS_FEEDBACK", "FEEDBACK", "PROCESS_FEEDBACK", "{}")));
  }

  private Map<String, Object> sqlTransformSpec() {
    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put(
        "sourceSql",
        """
        select tenant_id,
               account_id,
               biz_date,
               sum(amount) as total_amount,
               max(event_id) as high_water_mark
        from biz.process_order_event
        where tenant_id = :tenantId
          and biz_date = cast(:bizDate as date)
        group by tenant_id, account_id, biz_date
        """);
    spec.put("targetSchema", "biz");
    spec.put("targetTable", "process_account_summary");
    spec.put("writeMode", "UPSERT");
    spec.put(
        "columns",
        List.of(
            Map.of("source", "tenant_id", "target", "tenant_id"),
            Map.of("source", "account_id", "target", "account_id"),
            Map.of("source", "biz_date", "target", "biz_date"),
            Map.of("source", "total_amount", "target", "total_amount"),
            Map.of("source", "high_water_mark", "target", "high_water_mark")));
    spec.put("conflictColumns", List.of("tenant_id", "account_id", "biz_date"));
    spec.put("watermarkColumn", "high_water_mark");
    return spec;
  }

  private void seedCustomPluginPipelineDefinition(String jobCode) {
    Long pipelineDefinitionId =
        insertPipelineDefinition(jobCode, "e2e custom plugin process pipeline");
    insertProcessSteps(
        pipelineDefinitionId,
        List.of(
            new ProcessStepSeed("PROCESS_PREPARE", "PREPARE", "PROCESS_PREPARE", "{}"),
            new ProcessStepSeed(
                "PROCESS_COMPUTE", "COMPUTE", CUSTOM_PLUGIN_CODE, "{\"processedCount\":1}"),
            new ProcessStepSeed("PROCESS_VALIDATE", "VALIDATE", "PROCESS_VALIDATE", "{}"),
            new ProcessStepSeed("PROCESS_COMMIT", "COMMIT", "PROCESS_COMMIT", "{}"),
            new ProcessStepSeed("PROCESS_FEEDBACK", "FEEDBACK", "PROCESS_FEEDBACK", "{}")));
  }

  private Long insertPipelineDefinition(String jobCode, String pipelineName) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.pipeline_definition (
            tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group, version, enabled
        ) values (?, ?, ?, 'PROCESS', 'E2E', 'PROCESS', 1, true)
        returning id
        """,
        Long.class,
        TENANT,
        jobCode,
        pipelineName);
  }

  private void insertProcessSteps(Long pipelineDefinitionId, List<ProcessStepSeed> steps) {
    for (int i = 0; i < steps.size(); i++) {
      ProcessStepSeed step = steps.get(i);
      jdbcTemplate.update(
          """
          insert into batch.pipeline_step_definition (
              pipeline_definition_id, step_code, step_name, stage_code, step_order, impl_code, step_params
          ) values (?, ?, ?, ?, ?, ?, ?::jsonb)
          """,
          pipelineDefinitionId,
          step.stepCode(),
          step.stepCode(),
          step.stageCode(),
          i + 1,
          step.implCode(),
          step.stepParamsJson());
    }
  }

  private Long loadPipelineInstanceId(String dedupKey) {
    return jdbcTemplate.queryForObject(
        """
        select pi.id
        from batch.pipeline_instance pi
        join batch.job_instance ji on ji.id = pi.related_job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ? and pi.pipeline_type = 'PROCESS'
        """,
        Long.class,
        TENANT,
        dedupKey);
  }

  private Map<String, Object> loadProcessOutcome(String dedupKey) {
    return jdbcTemplate.queryForMap(
        """
        select t.task_status, ji.instance_status, ji.high_water_mark_out
        from batch.job_task t
        join batch.job_instance ji on ji.id = t.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """,
        TENANT,
        dedupKey);
  }

  private record ProcessStepSeed(
      String stepCode, String stageCode, String implCode, String stepParamsJson) {}

  /** 自定义 plugin 测试桩:只重写 compute(),其余 lifecycle 走 ProcessComputePlugin 默认 no-op。 */
  @TestConfiguration
  static class ProcessE2eTestConfiguration {

    @Bean
    ProcessComputePlugin e2eProcessComputePlugin() {
      return new ProcessComputePlugin() {
        @Override
        public String implCode() {
          return CUSTOM_PLUGIN_CODE;
        }

        @Override
        public ProcessStageResult compute(ProcessJobContext context) {
          Object stepParams =
              context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_CURRENT_STEP_PARAMS);
          if (stepParams instanceof Map<?, ?> params) {
            context.getAttributes().put("processedCount", params.get("processedCount"));
          }
          context
              .getAttributes()
              .put(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, CUSTOM_PLUGIN_WATERMARK);
          return ProcessStageResult.success(ProcessStage.COMPUTE);
        }
      };
    }
  }
}
