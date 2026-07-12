package io.github.pinpols.batch.worker.processes.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.testing.TestContainerImages;
import io.github.pinpols.batch.worker.core.config.WorkerCheckpointProperties;
import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import io.github.pinpols.batch.worker.processes.metrics.ProcessMetrics;
import io.github.pinpols.batch.worker.processes.sql.SqlTransformComputePlugin;
import io.github.pinpols.batch.worker.processes.sql.SqlTransformComputeSecurityProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P1 阶段级续跑(ADR-038 §决策四)真 PG 崩溃-重派 IT——照 P0 {@code LoadStepCheckpointCrashResumeIntegrationTest}
 * 样式,但覆盖 stage 之间的跳过(而非 stage 内行号续跑)。
 *
 * <p>用**真实 PG(Testcontainers)** 跑 {@link SqlTransformComputePlugin} 的 WAP staging → target 发布, 驱动完整
 * {@link DefaultProcessStageExecutor} 跨两次 attempt(同稳定 {@code batch-<taskId>} 键 = 同 staging)。 {@link
 * PlatformFileRuntimeRepository} 用 mock 模拟持久到平台库的 {@code pipeline_step_run}——{@code
 * loadSucceededStepCodes} 返回上一 attempt 已成功的 stepCode 集,即 P1 读取侧的输入。
 *
 * <p>验证三条 P1 契约:
 *
 * <ol>
 *   <li><b>崩溃前跳过、staging 复用不重算</b>:attempt1 COMPUTE 写 staging 后在 COMMIT 前崩溃;attempt2 开关开 +
 *       COMPUTE/VALIDATE 已成功 → 跳过(不重跑 COMPUTE 的 pre-DELETE + re-SELECT),直接 COMMIT 复用 attempt1 的
 *       staging 发布到 target,零重复。用「attempt2 前删空源表」证明 staging 确被复用而非重算。
 *   <li><b>副作用未完成不误跳</b>:succeeded 集为空(该 stage 从未成功)时,即使开关开也**必跑** COMPUTE (pre-DELETE 清残留 +
 *       重算),绝不误跳。用「预置陈旧 staging + 空源表」证明 COMPUTE 真的重跑清掉了陈旧行。
 *   <li><b>COMMIT 恒不跳</b>:COMMIT 不在 skip-safe 集,原子发布决策每次重派都重做。
 * </ol>
 */
class ProcessStageSkipCrashResumeIntegrationTest {

  private static final String TENANT = "t1";
  private static final Long PIPELINE_INSTANCE_ID = 7001L;
  private static final Long TASK_ID = 4242L;

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(TestContainerImages.POSTGRES);

  private JdbcTemplate jdbcTemplate;
  private SqlTransformComputePlugin plugin;
  private PlatformFileRuntimeRepository runtimeRepository;
  private DriverManagerDataSource dataSource;

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
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    plugin =
        new SqlTransformComputePlugin(
            dataSource, new ObjectMapper(), security, ProcessMetrics.noop());

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
    seedSource();

    runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    lenient().when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLong(inv.getArgument(0)));
    lenient().when(runtimeRepository.startStepRun(any(), any(), any(), any())).thenReturn(9999L);
  }

  private void seedSource() {
    jdbcTemplate.update(
        "insert into biz.order_event values (?, ?, ?, ?)",
        TENANT,
        "A",
        1L,
        new BigDecimal("10.00"));
    jdbcTemplate.update(
        "insert into biz.order_event values (?, ?, ?, ?)",
        TENANT,
        "A",
        2L,
        new BigDecimal("20.00"));
    jdbcTemplate.update(
        "insert into biz.order_event values (?, ?, ?, ?)", TENANT, "B", 3L, new BigDecimal("7.00"));
  }

  @Test
  @DisplayName("COMMIT 前崩溃重派:开关开跳过已成功 COMPUTE/VALIDATE,复用 staging 原子发布,零重复")
  void crashBeforeCommit_resumeSkipsComputeAndReusesStaging_noRecomputeNoDuplicate() {
    // ── Attempt 1:跑 PREPARE→COMPUTE→VALIDATE 后在 COMMIT 前"崩溃"(步骤定义只到 VALIDATE)──
    DefaultProcessStageExecutor executorNoSkip = newExecutor(disabledStageSkip());
    ProcessJobContext attempt1 = newContext(stepsThrough(ProcessStage.VALIDATE));
    List<ProcessStageResult> r1 = executorNoSkip.execute(attempt1);
    assertThat(r1).allMatch(ProcessStageResult::success);
    // staging 已落 2 行(A/B 聚合),target 仍空(COMMIT 未跑)
    assertThat(stagingCount()).isEqualTo(2);
    assertThat(targetCount()).isZero();

    // ── 关键:attempt2 前把源表删空。若 COMPUTE 被重跑,pre-DELETE 会清 staging + 从空源 re-SELECT
    //    → staging 归零 → target 0;只有真正"跳过 COMPUTE 复用 staging"才能发布出 2 行。──
    jdbcTemplate.execute("delete from biz.order_event");

    // ── Attempt 2:开关开,COMPUTE/VALIDATE 已成功 → 跳过,直达 COMMIT 复用 staging ──
    when(runtimeRepository.loadSucceededStepCodes(PIPELINE_INSTANCE_ID))
        .thenReturn(Set.of("PROCESS_COMPUTE", "PROCESS_VALIDATE"));
    DefaultProcessStageExecutor executorSkip = newExecutor(enabledStageSkip());
    ProcessJobContext attempt2 = newContext(stepsThrough(ProcessStage.FEEDBACK));
    List<ProcessStageResult> r2 = executorSkip.execute(attempt2);

    assertThat(r2).allMatch(ProcessStageResult::success);
    // 跳过的 COMPUTE/VALIDATE 不进 results:只剩 PREPARE + COMMIT + FEEDBACK
    assertThat(r2)
        .extracting(ProcessStageResult::stage)
        .containsExactly(ProcessStage.PREPARE, ProcessStage.COMMIT, ProcessStage.FEEDBACK);
    // 复用 attempt1 的 staging 发布:target 恰好 2 行,数值正确,零重复
    assertThat(targetCount()).isEqualTo(2);
    assertThat(totalAmountOf("A")).isEqualByComparingTo("30.00"); // A: 10.00 + 20.00
    assertThat(totalAmountOf("B")).isEqualByComparingTo("7.00");
    // FEEDBACK 清了 staging
    assertThat(stagingCount()).isZero();
  }

  @Test
  @DisplayName("从未成功的 stage 不误跳:succeeded 集为空 → COMPUTE 必跑并清掉陈旧 staging")
  void noPriorSuccess_doesNotSkipCompute_recomputesAndClearsStaleStaging() {
    // 预置一条陈旧 staging(模拟上次崩溃残留),但 COMPUTE 从未成功过(succeeded 集空)。
    jdbcTemplate.update(
        """
        insert into batch.process_staging (batch_key, tenant_id, target_schema, target_table, payload)
        values (?, ?, 'biz', 'account_summary',
                '{"tenant_id":"t1","account_id":"STALE","total_amount":999.00,"high_water_mark":1}'::jsonb)
        """,
        "process-" + TASK_ID,
        TENANT);
    // 源表删空:若 COMPUTE 被误跳,陈旧 STALE 行会被 COMMIT 发布到 target;
    // 若 COMPUTE 正确重跑,pre-DELETE 清掉 STALE + 从空源 re-SELECT → staging 空 → target 空。
    jdbcTemplate.execute("delete from biz.order_event");

    when(runtimeRepository.loadSucceededStepCodes(PIPELINE_INSTANCE_ID)).thenReturn(Set.of());
    DefaultProcessStageExecutor executorSkip = newExecutor(enabledStageSkip());
    ProcessJobContext context = newContext(stepsThrough(ProcessStage.FEEDBACK));
    List<ProcessStageResult> results = executorSkip.execute(context);

    assertThat(results).allMatch(ProcessStageResult::success);
    // COMPUTE 真跑了(未误跳):陈旧 STALE 被 pre-DELETE 清掉,空源无新行 → target 零
    assertThat(targetCount()).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from biz.account_summary where account_id='STALE'", Integer.class))
        .isZero();
  }

  // ─── helpers ────────────────────────────────────────────────────────────────

  private DefaultProcessStageExecutor newExecutor(WorkerCheckpointProperties checkpointProps) {
    List<ProcessStageStep> steps =
        List.of(
            new PrepareStep(),
            new ComputeStep(),
            new ValidateStep(),
            new CommitStep(),
            new FeedbackStep(ProcessMetrics.noop()));
    return new DefaultProcessStageExecutor(
        steps, List.of(plugin), runtimeRepository, ProcessMetrics.noop(), checkpointProps);
  }

  private ProcessJobContext newContext(List<PipelineStepDefinition> steps) {
    ProcessJobContext context = new ProcessJobContext();
    context.setTenantId(TENANT);
    context.setJobCode("JOB_PROCESS");
    context.setWorkerId("process-test");
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    context.getAttributes().put(PipelineRuntimeKeys.TASK_ID, TASK_ID);
    context.getAttributes().put(PipelineRuntimeKeys.HIGH_WATER_MARK_IN, 0L);
    // spec 落在 COMPUTE step 的 step_params 上(与生产一致):executor 的 resolvePluginAndAttachToContext
    // 会把它拷进 PROCESS_COMPUTE_STEP_PARAMS 供 plugin 各 lifecycle 读取。
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, steps);
    return context;
  }

  /** 构造到 {@code lastStage}(含)为止的步骤定义;COMPUTE step 的 implCode = plugin id 以命中插件解析。 */
  private List<PipelineStepDefinition> stepsThrough(ProcessStage lastStage) {
    List<PipelineStepDefinition> steps = new ArrayList<>();
    int order = 1;
    for (ProcessStage stage :
        List.of(
            ProcessStage.PREPARE,
            ProcessStage.COMPUTE,
            ProcessStage.VALIDATE,
            ProcessStage.COMMIT,
            ProcessStage.FEEDBACK)) {
      String implCode =
          stage == ProcessStage.COMPUTE ? SqlTransformComputePlugin.PLUGIN_ID : "PROCESS_" + stage;
      Map<String, Object> stepParams =
          stage == ProcessStage.COMPUTE ? computeStepParams() : Map.of();
      steps.add(
          PipelineStepDefinition.builder()
              .id((long) order)
              .pipelineDefinitionId(1L)
              .stepCode("PROCESS_" + stage.name())
              .stepName("Process " + stage.name())
              .stageCode(stage.name())
              .stepOrder(order++)
              .implCode(implCode)
              .stepParams(stepParams)
              .timeoutSeconds(0)
              .retryPolicy("NONE")
              .retryMaxCount(0)
              .enabled(true)
              .build());
      if (stage == lastStage) {
        break;
      }
    }
    return steps;
  }

  private Map<String, Object> computeStepParams() {
    Map<String, Object> sqlTransformSpec = new LinkedHashMap<>();
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
    // 空源(负向用例:COMPUTE 重跑清残留后从空源 re-SELECT)时 VALIDATE 走 SUCCESS 而非 STAGED_EMPTY 失败。
    sqlTransformSpec.put("emptyResultPolicy", "SUCCESS");
    Map<String, Object> stepParams = new LinkedHashMap<>();
    stepParams.put("sqlTransformCompute", sqlTransformSpec);
    return stepParams;
  }

  private int stagingCount() {
    Integer n =
        jdbcTemplate.queryForObject("select count(*) from batch.process_staging", Integer.class);
    return n == null ? 0 : n;
  }

  private int targetCount() {
    Integer n =
        jdbcTemplate.queryForObject("select count(*) from biz.account_summary", Integer.class);
    return n == null ? 0 : n;
  }

  private BigDecimal totalAmountOf(String accountId) {
    return jdbcTemplate.queryForObject(
        "select total_amount from biz.account_summary where account_id = ?",
        BigDecimal.class,
        accountId);
  }

  private WorkerCheckpointProperties disabledStageSkip() {
    return new WorkerCheckpointProperties();
  }

  private WorkerCheckpointProperties enabledStageSkip() {
    WorkerCheckpointProperties properties = new WorkerCheckpointProperties();
    properties.getStageSkip().setEnabled(true);
    return properties;
  }

  private static Long toLong(Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof Number n) {
      return n.longValue();
    }
    if (v instanceof String s && !s.isBlank()) {
      try {
        return Long.parseLong(s);
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }
}
