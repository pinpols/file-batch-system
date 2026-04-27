package com.example.batch.e2e.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PROCESS e2e 共享 fixture：业务行 seed、pipeline_definition + pipeline_step_definition seed、 SQL
 * transform spec 构造，避免 happy 与 failure 两个 IT 类重复维护。
 *
 * <p>schema 由 {@link E2eTestSql#BIZ_SCHEMA} 通过 {@code @Sql} 加载（{@code create_biz_tables.sql}），本类只负责
 * 行级 seed / 清理 / pipeline 定义注入。
 */
public final class ProcessE2eFixture {

  private ProcessE2eFixture() {}

  /** 清空 PROCESS 业务表行 + staging，确保测试间互相独立。表本身由 BIZ_SCHEMA 创建。 */
  public static void cleanProcessRows(JdbcTemplate jdbcTemplate) {
    jdbcTemplate.execute("delete from biz.process_order_event");
    jdbcTemplate.execute("delete from biz.process_account_summary");
    jdbcTemplate.execute("delete from batch.process_staging");
  }

  /** 写入 sqlTransform e2e 的演示数据：A=10+20、B=7。 */
  public static void seedDemoOrderEvents(JdbcTemplate jdbcTemplate, String tenantId) {
    jdbcTemplate.update(
        """
        insert into biz.process_order_event values
          (?, 'A', date '2026-01-15', 1, 10.00),
          (?, 'A', date '2026-01-15', 2, 20.00),
          (?, 'B', date '2026-01-15', 3, 7.00)
        """,
        tenantId,
        tenantId,
        tenantId);
  }

  /** 写入 sqlTransform PROCESS pipeline 定义（5 stage：PREPARE/COMPUTE/VALIDATE/COMMIT/FEEDBACK）。 */
  public static Long seedSqlTransformPipelineDefinition(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      String tenantId,
      String jobCode,
      List<Map<String, Object>> validations)
      throws JsonProcessingException {
    Long pipelineDefinitionId =
        insertPipelineDefinition(jdbcTemplate, tenantId, jobCode, "e2e wap sql transform pipeline");
    Map<String, Object> spec = sqlTransformSpec();
    if (!validations.isEmpty()) {
      spec.put("validations", validations);
    }
    Map<String, Object> stepParams = Map.of("sqlTransformCompute", spec);

    insertProcessSteps(
        jdbcTemplate,
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
    return pipelineDefinitionId;
  }

  /** 写入自定义 plugin 路径的 PROCESS pipeline 定义（只重写 COMPUTE 的 implCode，其他 stage 默认 no-op）。 */
  public static Long seedCustomPluginPipelineDefinition(
      JdbcTemplate jdbcTemplate,
      String tenantId,
      String jobCode,
      String customComputeImplCode,
      String computeStepParamsJson) {
    Long pipelineDefinitionId =
        insertPipelineDefinition(
            jdbcTemplate, tenantId, jobCode, "e2e custom plugin process pipeline");
    insertProcessSteps(
        jdbcTemplate,
        pipelineDefinitionId,
        List.of(
            new ProcessStepSeed("PROCESS_PREPARE", "PREPARE", "PROCESS_PREPARE", "{}"),
            new ProcessStepSeed(
                "PROCESS_COMPUTE", "COMPUTE", customComputeImplCode, computeStepParamsJson),
            new ProcessStepSeed("PROCESS_VALIDATE", "VALIDATE", "PROCESS_VALIDATE", "{}"),
            new ProcessStepSeed("PROCESS_COMMIT", "COMMIT", "PROCESS_COMMIT", "{}"),
            new ProcessStepSeed("PROCESS_FEEDBACK", "FEEDBACK", "PROCESS_FEEDBACK", "{}")));
    return pipelineDefinitionId;
  }

  private static Long insertPipelineDefinition(
      JdbcTemplate jdbcTemplate, String tenantId, String jobCode, String pipelineName) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.pipeline_definition (
            tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group, version, enabled
        ) values (?, ?, ?, 'PROCESS', 'E2E', 'PROCESS', 1, true)
        returning id
        """,
        Long.class,
        tenantId,
        jobCode,
        pipelineName);
  }

  private static void insertProcessSteps(
      JdbcTemplate jdbcTemplate, Long pipelineDefinitionId, List<ProcessStepSeed> steps) {
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

  private static Map<String, Object> sqlTransformSpec() {
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

  private record ProcessStepSeed(
      String stepCode, String stageCode, String implCode, String stepParamsJson) {}
}
