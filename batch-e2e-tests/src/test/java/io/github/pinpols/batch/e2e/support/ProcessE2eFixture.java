package io.github.pinpols.batch.e2e.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.SystemPipelineTaskKeys;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PROCESS e2e 共享 fixture：业务行 seed、作业执行参数配置和 SQL transform spec 构造，避免 happy
 * 与 failure 两个 IT 类重复维护。
 *
 * <p>业务 schema 由 {@link E2eBusinessSchema} 在独立 e2eBusinessDataSource 上加载。本类不写入租户 Pipeline
 * 定义；场景参数保存在 Job 默认执行参数中，由系统 Pipeline 执行。
 */
public final class ProcessE2eFixture {

  private ProcessE2eFixture() {}

  /** 清空 PROCESS 业务表行 + staging，确保测试间互相独立。表本身由 E2eBusinessSchema 创建。 */
  public static void cleanProcessRows(JdbcTemplate jdbcTemplate) {
    final String deleteProcessOrderEventSql = """
        delete from biz.process_order_event
    """;
    final String deleteProcessAccountSummarySql = """
        delete from biz.process_account_summary
    """;
    final String deleteProcessStagingSql = """
        delete from batch.process_staging
    """;

    jdbcTemplate.execute(deleteProcessOrderEventSql);
    jdbcTemplate.execute(deleteProcessAccountSummarySql);
    jdbcTemplate.execute(deleteProcessStagingSql);
  }

  /** 写入 sqlTransform e2e 的演示数据：A=10+20、B=7。 */
  public static void seedDemoOrderEvents(JdbcTemplate jdbcTemplate, String tenantId) {
    seedDemoOrderEvents(jdbcTemplate, tenantId, LocalDate.of(2026, 1, 15));
  }

  /** 按测试业务日写入 sqlTransform e2e 的演示数据：A=10+20、B=7。 */
  public static void seedDemoOrderEvents(
      JdbcTemplate jdbcTemplate, String tenantId, LocalDate bizDate) {
    final String insertProcessOrderEventSql = """
        insert into biz.process_order_event values
          (?, 'A', ?, 1, 10.00),
          (?, 'A', ?, 2, 20.00),
          (?, 'B', ?, 3, 7.00)
        """;

    jdbcTemplate.update(
        insertProcessOrderEventSql, tenantId, bizDate, tenantId, bizDate, tenantId, bizDate);
  }

  /** 配置系统 PROCESS Pipeline 的 SQL transform 运行参数。 */
  public static void configureSqlTransformJobParameters(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      String tenantId,
      String jobCode,
      List<Map<String, Object>> validations)
      throws JsonProcessingException {
    Map<String, Object> spec = sqlTransformSpec();
    if (!validations.isEmpty()) {
      spec.put("validations", validations);
    }
    Map<String, Object> params = Map.of(
        "processImplCode",
        "sqlTransformCompute",
        SystemPipelineTaskKeys.STEP_PARAMS,
        Map.of("COMPUTE", Map.of("sqlTransformCompute", spec)));
    updateJobParameters(jdbcTemplate, objectMapper, tenantId, jobCode, params);
  }

  /** 为 PROCESS 重试生命周期 E2E 配置确定性的账户到分区路由。 */
  public static void configurePartitionedSqlTransformJobParameters(
      JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, String tenantId, String jobCode)
      throws JsonProcessingException {
    Map<String, Object> spec = sqlTransformSpec();
    spec.put("sourceSql", """
        select tenant_id,
               account_id,
               biz_date,
               sum(amount) as total_amount,
               max(event_id) as high_water_mark
        from biz.process_order_event
        where tenant_id = :tenantId
          and biz_date = cast(:bizDate as date)
          and case account_id when 'A' then 1 else 2 end = :partitionNo
        group by tenant_id, account_id, biz_date
        """);
    Map<String, Object> params = Map.of(
        "processImplCode",
        "sqlTransformCompute",
        SystemPipelineTaskKeys.STEP_PARAMS,
        Map.of("COMPUTE", Map.of("sqlTransformCompute", spec)));
    updateJobParameters(jdbcTemplate, objectMapper, tenantId, jobCode, params);
  }

  /** 配置系统 PROCESS Pipeline 的自定义 COMPUTE 插件参数。 */
  public static void configureCustomPluginJobParameters(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      String tenantId,
      String jobCode,
      String customComputeImplCode,
      String computeStepParamsJson)
      throws JsonProcessingException {
    Map<String, Object> computeParams = objectMapper.readValue(
        computeStepParamsJson,
        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
    Map<String, Object> params = Map.of(
        "processImplCode",
        customComputeImplCode,
        SystemPipelineTaskKeys.STEP_PARAMS,
        Map.of("COMPUTE", Map.of(customComputeImplCode, computeParams)));
    updateJobParameters(jdbcTemplate, objectMapper, tenantId, jobCode, params);
  }

  private static void updateJobParameters(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      String tenantId,
      String jobCode,
      Map<String, Object> parameters)
      throws JsonProcessingException {
    final String updateJobDefinitionSql = """
        update batch.job_definition
           set default_params = coalesce(default_params, '{}'::jsonb) || ?::jsonb,
               updated_at = current_timestamp
         where tenant_id = ? and job_code = ?
        """;

    int updated = jdbcTemplate.update(
        updateJobDefinitionSql, objectMapper.writeValueAsString(parameters), tenantId, jobCode);
    if (updated != 1) {
      throw new IllegalStateException("Expected one PROCESS Job definition to update");
    }
  }

  private static Map<String, Object> sqlTransformSpec() {
    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("sourceSql", """
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
}
