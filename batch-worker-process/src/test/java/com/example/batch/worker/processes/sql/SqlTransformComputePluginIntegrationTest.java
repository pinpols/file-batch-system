package com.example.batch.worker.processes.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessStageResult;
import com.example.batch.worker.processes.stage.ProcessRuntimeKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * SqlTransformComputePlugin WAP+bookends 端到端集成测试。
 *
 * <p>每个测试都通过 prepare → compute → validate → commit → feedback 五个 lifecycle 方法,验证:
 *
 * <ul>
 *   <li>compute 把行写到 batch.process_staging 而不是直接写 target
 *   <li>validate 在 staging 上跑校验,失败时阻断 commit
 *   <li>commit 用 jsonb_populate_record 把 staging 反序列化到 target,带 ON CONFLICT
 *   <li>feedback 清空 staging,水位通过 attributes 传递回 worker(由 worker 上报 orchestrator)
 * </ul>
 */
class SqlTransformComputePluginIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private JdbcTemplate jdbcTemplate;
  private SqlTransformComputePlugin plugin;

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @BeforeEach
  void setUp() {
    org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    plugin = new SqlTransformComputePlugin(dataSource, new ObjectMapper(), security);

    jdbcTemplate.execute("drop schema if exists biz cascade");
    jdbcTemplate.execute("drop schema if exists batch cascade");
    jdbcTemplate.execute("create schema biz");
    jdbcTemplate.execute("create schema batch");
    jdbcTemplate.execute(
        """
        create table batch.process_staging (
          batch_key text not null,
          row_seq bigserial not null,
          tenant_id text not null,
          target_schema text not null,
          target_table text not null,
          payload jsonb not null,
          staged_at timestamptz not null default now(),
          primary key (batch_key, row_seq)
        )
        """);
    jdbcTemplate.execute(
        """
        create table biz.order_event (
          tenant_id varchar(32) not null,
          account_id varchar(32) not null,
          event_id bigint not null,
          amount numeric(18, 2) not null
        )
        """);
    jdbcTemplate.execute(
        """
        create table biz.account_summary (
          tenant_id varchar(32) not null,
          account_id varchar(32) not null,
          total_amount numeric(18, 2) not null,
          high_water_mark bigint not null,
          primary key (tenant_id, account_id)
        )
        """);
    jdbcTemplate.update(
        "insert into biz.order_event values (?, ?, ?, ?)", "t1", "A", 1L, new BigDecimal("10.00"));
    jdbcTemplate.update(
        "insert into biz.order_event values (?, ?, ?, ?)", "t1", "A", 2L, new BigDecimal("20.00"));
    jdbcTemplate.update(
        "insert into biz.order_event values (?, ?, ?, ?)", "t1", "B", 3L, new BigDecimal("7.00"));
  }

  @Test
  void wapLifecycle_executesEndToEnd_andAdvancesWatermark() {
    ProcessJobContext context = newContextWithSpec();

    plugin.prepare(context);
    ProcessStageResult compute = plugin.compute(context);
    ProcessStageResult validate = plugin.validate(context);
    ProcessStageResult commit = plugin.commit(context);
    ProcessStageResult feedback = plugin.feedback(context);

    assertThat(compute.success()).isTrue();
    assertThat(validate.success()).isTrue();
    assertThat(commit.success()).isTrue();
    assertThat(feedback.success()).isTrue();

    // 业务结果落到目标表
    assertThat(
            jdbcTemplate.queryForObject(
                "select total_amount from biz.account_summary where account_id='A'",
                BigDecimal.class))
        .isEqualByComparingTo("20.00");
    assertThat(
            jdbcTemplate.queryForObject(
                "select total_amount from biz.account_summary where account_id='B'",
                BigDecimal.class))
        .isEqualByComparingTo("7.00");
    assertThat(
            jdbcTemplate.queryForObject("select count(*) from biz.account_summary", Integer.class))
        .isEqualTo(2);

    // staging 已被 feedback 清空
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from batch.process_staging", Integer.class))
        .isZero();

    // 水位、行数都正确暴露在 context attributes
    assertThat(context.getAttributes())
        .containsEntry(ProcessRuntimeKeys.PROCESS_STAGED_COUNT, 2)
        .containsEntry(ProcessRuntimeKeys.PROCESS_PUBLISHED_COUNT, 2)
        .containsEntry("processedCount", 2)
        .containsEntry(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, "3");
  }

  @Test
  void validate_failsAndAbortsBeforeCommit_whenUserRuleReturnsFalse() {
    ProcessJobContext context = newContextWithSpec();
    Map<String, Object> stepParams = currentStepParams(context);
    Map<String, Object> spec = nestedSpec(stepParams);
    spec.put(
        "validations",
        List.of(
            Map.of(
                "name",
                "non_negative_total",
                "checkSql",
                "select bool_and((payload->>'total_amount')::numeric > 100) AS pass,"
                    + " 'total_amount must exceed 100' AS message"
                    + " from batch.process_staging where batch_key = :batchKey")));

    plugin.prepare(context);
    plugin.compute(context);
    ProcessStageResult validate = plugin.validate(context);

    assertThat(validate.success()).isFalse();
    assertThat(validate.code()).isEqualTo("PROCESS_VALIDATION_FAILED");
    assertThat(validate.message()).contains("non_negative_total").contains("must exceed 100");

    // commit 没跑,target 应为空
    assertThat(
            jdbcTemplate.queryForObject("select count(*) from biz.account_summary", Integer.class))
        .isZero();
    // staging 仍有 2 行(留作 forensics,直到下次 prepare/feedback 才清)
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from batch.process_staging", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void prepare_failsFast_whenTargetTableDoesNotExist() {
    ProcessJobContext context = newContextWithSpec();
    Map<String, Object> spec = nestedSpec(currentStepParams(context));
    spec.put("targetTable", "nonexistent_target");

    assertThatThrownBy(() -> plugin.prepare(context))
        .hasMessageContaining("target table not found");
    // staging 完全空(prepare 早停)
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from batch.process_staging", Integer.class))
        .isZero();
  }

  @Test
  void prepare_failsFast_whenSchemaNotInAllowlist() {
    ProcessJobContext context = newContextWithSpec();
    Map<String, Object> spec = nestedSpec(currentStepParams(context));
    spec.put("targetSchema", "forbidden");

    assertThatThrownBy(() -> plugin.prepare(context))
        .hasMessageContaining("schema not allowlisted")
        .hasMessageContaining("forbidden");
  }

  // ─── 辅助 ────────────────────────────────────────────────────────────────────

  private ProcessJobContext newContextWithSpec() {
    ProcessJobContext context = new ProcessJobContext();
    context.setTenantId("t1");
    context.setJobCode("JOB_PROCESS");
    context.setWorkerId("process-test");
    context.setBatchKey("batch-test-1");
    context.getAttributes().put(PipelineRuntimeKeys.HIGH_WATER_MARK_IN, 1L);
    Map<String, Object> sqlTransformSpec = new java.util.LinkedHashMap<>();
    sqlTransformSpec.put(
        "sourceSql",
        """
        select tenant_id,
               account_id,
               sum(amount) as total_amount,
               max(event_id) as high_water_mark
        from biz.order_event
        where tenant_id = :tenantId
          and event_id > cast(:highWaterMarkIn as bigint)
        group by tenant_id, account_id
        """);
    sqlTransformSpec.put("targetSchema", "biz");
    sqlTransformSpec.put("targetTable", "account_summary");
    sqlTransformSpec.put("writeMode", "UPSERT");
    sqlTransformSpec.put(
        "columns",
        List.of(
            Map.of("source", "tenant_id", "target", "tenant_id"),
            Map.of("source", "account_id", "target", "account_id"),
            Map.of("source", "total_amount", "target", "total_amount"),
            Map.of("source", "high_water_mark", "target", "high_water_mark")));
    sqlTransformSpec.put("conflictColumns", List.of("tenant_id", "account_id"));
    sqlTransformSpec.put("watermarkColumn", "high_water_mark");
    Map<String, Object> stepParams = new java.util.LinkedHashMap<>();
    stepParams.put("sqlTransformCompute", sqlTransformSpec);
    context.getAttributes().put(ProcessRuntimeKeys.PROCESS_COMPUTE_STEP_PARAMS, stepParams);
    return context;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> currentStepParams(ProcessJobContext context) {
    return (Map<String, Object>)
        context.getAttributes().get(ProcessRuntimeKeys.PROCESS_COMPUTE_STEP_PARAMS);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> nestedSpec(Map<String, Object> stepParams) {
    return (Map<String, Object>) stepParams.get("sqlTransformCompute");
  }
}
