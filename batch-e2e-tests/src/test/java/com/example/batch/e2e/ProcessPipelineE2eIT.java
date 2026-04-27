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
import com.example.batch.e2e.support.E2eTestSql;
import com.example.batch.e2e.support.ProcessE2eFixture;
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
import org.springframework.test.context.jdbc.Sql;

/**
 * 端到端测试：PROCESS 主链路成功闭环（WAP+bookends 完整 5 stage）。
 *
 * <p>链路路径：
 *
 * <pre>
 * launch → orchestrator 调度/落 outbox → Kafka 派发 → process worker claim →
 *     PREPARE → COMPUTE（写 staging）→ VALIDATE → COMMIT（原子写 target）→ FEEDBACK（清 staging + 上报水位）
 *     → worker report → orchestrator 终态
 * </pre>
 *
 * <p>本类只覆盖成功路径（sqlTransformCompute 与自定义 plugin）；失败路径（validation 失败）见 {@link
 * ProcessFailurePipelineE2eIT}。
 */
@SpringBootTest(
    classes = E2eProcessApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.process.worker-type=PROCESS")
@ActiveProfiles({"test", "e2e"})
@Sql(scripts = {E2eTestSql.BIZ_SCHEMA})
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
    ProcessE2eFixture.cleanProcessRows(jdbcTemplate);
    ProcessE2eFixture.seedDemoOrderEvents(jdbcTemplate, TENANT);
    ProcessE2eFixture.seedSqlTransformPipelineDefinition(
        jdbcTemplate, objectMapper, TENANT, seed.jobCode(), List.of());

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
  void wap_customPlugin_simpleComputeOnly_runsAll5StagesAsNoOpForOthers() {
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate, TENANT, "PROCESS", "process", TriggerType.API);
    ProcessE2eFixture.cleanProcessRows(jdbcTemplate);
    ProcessE2eFixture.seedCustomPluginPipelineDefinition(
        jdbcTemplate, TENANT, seed.jobCode(), CUSTOM_PLUGIN_CODE, "{\"processedCount\":1}");

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
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*)::int from batch.pipeline_step_run where pipeline_instance_id=?",
                Integer.class,
                pipelineInstanceId))
        .isEqualTo(5);
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
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*)::int from batch.process_staging", Integer.class))
        .isZero();
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

  /** 自定义 plugin 测试桩：只重写 compute()，其余 lifecycle 走 ProcessComputePlugin 默认 no-op。 */
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
