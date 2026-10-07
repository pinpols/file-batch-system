package io.github.pinpols.batch.orchestrator.application.service.dryrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanBuilder;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.dryrun.JdbcDryRunSqlProbe;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("试运行计划服务: 定义校验, 计划生成与执行探针拦截口径")
class DefaultDryRunPlanServiceTest {

  private OrchestratorConfigCacheService configCache;
  private SchedulePlanBuilder planBuilder;
  private DefaultDryRunPlanService service;
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    configCache = mock(OrchestratorConfigCacheService.class);
    planBuilder = mock(SchedulePlanBuilder.class);
    WorkflowNodeMapper nodeMapper = mock(WorkflowNodeMapper.class);
    WorkflowEdgeMapper edgeMapper = mock(WorkflowEdgeMapper.class);
    BatchTimezoneProvider tz = new BatchTimezoneProvider(new BatchTimezoneProperties());
    jdbcTemplate = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<JdbcTemplate> jdbcTemplateProvider = mock(ObjectProvider.class);
    when(jdbcTemplateProvider.getIfAvailable()).thenReturn(jdbcTemplate);
    DryRunObjectStorageProbe objectStorageProbe = mock(DryRunObjectStorageProbe.class);
    service = new DefaultDryRunPlanService(
        configCache,
        planBuilder,
        nodeMapper,
        edgeMapper,
        tz,
        new JdbcDryRunSqlProbe(jdbcTemplateProvider),
        objectStorageProbe,
        request -> new OutboundHttpResponse(200, ""));
  }

  @Test
  @DisplayName("作业定义与流程定义都不存在时给出错误提示, 计划判定失败")
  void shouldReportError_whenJobDefinitionMissing() {
    when(configCache.findEnabledJobDefinition("t1", "JOB_A")).thenReturn(null);
    when(configCache.findEnabledWorkflowDefinition("t1", "JOB_A")).thenReturn(null);

    DryRunPlanResult result = service.plan(DryRunPlanRequest.builder()
        .tenantId("t1")
        .jobCode("JOB_A")
        .level(DryRunLevel.CONFIG_VALIDATE)
        .build());

    assertThat(result.success()).isFalse();
    assertThat(result.findings())
        .extracting(DryRunFinding::code)
        .contains("JOB_DEFINITION_NOT_FOUND");
  }

  @Test
  @DisplayName("调度表达式合法时计划通过并给出表达式正常提示")
  void shouldPass_whenCronExpressionValid() {
    when(configCache.findEnabledJobDefinition("t1", "JOB_A"))
        .thenReturn(JobDefinitionEntity.builder()
            .id(1L)
            .tenantId("t1")
            .jobCode("JOB_A")
            .scheduleType("CRON")
            .scheduleExpr("0 0 * * * ?")
            .timezone("Asia/Shanghai")
            .build());

    DryRunPlanResult result = service.plan(DryRunPlanRequest.builder()
        .tenantId("t1")
        .jobCode("JOB_A")
        .level(DryRunLevel.CONFIG_VALIDATE)
        .build());

    assertThat(result.success()).isTrue();
    assertThat(result.findings()).extracting(DryRunFinding::code).contains("JOB_CRON_OK");
  }

  @Test
  @DisplayName("调度表达式非法时给出错误提示, 计划判定失败")
  void shouldReportError_whenCronExpressionInvalid() {
    when(configCache.findEnabledJobDefinition("t1", "JOB_A"))
        .thenReturn(JobDefinitionEntity.builder()
            .id(1L)
            .tenantId("t1")
            .jobCode("JOB_A")
            .scheduleType("CRON")
            .scheduleExpr("invalid cron")
            .timezone("Asia/Shanghai")
            .build());

    DryRunPlanResult result = service.plan(DryRunPlanRequest.builder()
        .tenantId("t1")
        .jobCode("JOB_A")
        .level(DryRunLevel.CONFIG_VALIDATE)
        .build());

    assertThat(result.success()).isFalse();
    assertThat(result.findings())
        .extracting(DryRunFinding::code)
        .contains("JOB_SCHEDULE_EXPR_INVALID");
  }

  @Test
  @DisplayName("缺少营业日时给出参数缺失提示, 计划判定失败")
  void shouldReportError_whenBizDateMissing() {
    DryRunPlanResult result = service.plan(DryRunPlanRequest.builder()
        .tenantId("t1")
        .jobCode("JOB_A")
        .level(DryRunLevel.SCHEDULE_PLAN)
        .build());

    assertThat(result.success()).isFalse();
    assertThat(result.findings()).extracting(DryRunFinding::code).contains("BIZDATE_MISSING");
  }

  @Test
  @DisplayName("生成调度计划时给出分区数量等汇总信息")
  void shouldEmitScheduleSummary_whenPlanBuilt() {
    when(configCache.findEnabledJobDefinition("t1", "JOB_A"))
        .thenReturn(JobDefinitionEntity.builder()
            .id(1L)
            .tenantId("t1")
            .jobCode("JOB_A")
            .scheduleType("MANUAL")
            .build());
    SchedulePlan plan = new SchedulePlan();
    plan.setQueueCode("Q");
    plan.setWorkerGroup("WG");
    plan.setDefaultWorkerType("IMPORT");
    plan.setPriority(5);
    plan.setPartitionCount(3);
    plan.getPartitions().add(new SchedulePlan.PartitionPlan());
    plan.getPartitions().add(new SchedulePlan.PartitionPlan());
    plan.getPartitions().add(new SchedulePlan.PartitionPlan());
    when(planBuilder.build(any())).thenReturn(plan);

    DryRunPlanResult result = service.plan(DryRunPlanRequest.builder()
        .tenantId("t1")
        .jobCode("JOB_A")
        .bizDate(LocalDate.of(2026, Month.MAY, 7))
        .level(DryRunLevel.SCHEDULE_PLAN)
        .params(Map.of())
        .build());

    assertThat(result.success()).isTrue();
    assertThat(result.summary())
        .containsEntry("workerGroup", "WG")
        .containsEntry("partitionCount", 3)
        .containsEntry("partitions", 3);
  }

  @Test
  @DisplayName("执行级试运行继承计划能力并给出执行侧汇总提示")
  void shouldInheritPlanAndEmitExecutionSummary_whenLevelThree() {
    when(configCache.findEnabledJobDefinition("t1", "JOB_A"))
        .thenReturn(JobDefinitionEntity.builder().id(1L).scheduleType("MANUAL").build());
    SchedulePlan plan = new SchedulePlan();
    plan.setPartitionCount(1);
    plan.getPartitions().add(new SchedulePlan.PartitionPlan());
    when(planBuilder.build(any())).thenReturn(plan);

    DryRunPlanResult result = service.plan(DryRunPlanRequest.builder()
        .tenantId("t1")
        .jobCode("JOB_A")
        .bizDate(LocalDate.of(2026, Month.MAY, 7))
        .level(DryRunLevel.EXECUTION_PLAN)
        .build());

    assertThat(result.success()).isTrue();
    // L3 真接后：无 SQL/对象存储/endpoint params 时返回 EXEC_PLAN_NO_PROBES_TRIGGERED
    assertThat(result.findings())
        .extracting(DryRunFinding::code)
        .contains("EXEC_PLAN_NO_PROBES_TRIGGERED");
    assertThat(result.summary())
        .containsEntry("l3SqlProbed", 0)
        .containsEntry("l3S3Probed", 0)
        .containsEntry("l3EndpointProbed", 0);
  }

  @Test
  @DisplayName("参数模板声明必填但未提供时给出参数缺失提示")
  void shouldRequireParams_whenSchemaDeclaresRequired() {
    when(configCache.findEnabledJobDefinition("t1", "JOB_A"))
        .thenReturn(JobDefinitionEntity.builder()
            .id(1L)
            .scheduleType("MANUAL")
            .paramSchema(Map.of("required", List.of("targetTable")))
            .build());

    DryRunPlanResult result = service.plan(DryRunPlanRequest.builder()
        .tenantId("t1")
        .jobCode("JOB_A")
        .level(DryRunLevel.CONFIG_VALIDATE)
        .build());

    assertThat(result.success()).isFalse();
    assertThat(result.findings()).extracting(DryRunFinding::code).contains("JOB_PARAMS_MISSING");
  }

  // ── S1: dry-run SQL 探针分号堆叠拒绝 ──────────────────────────────────────
  private DryRunPlanResult probeExecutionSql(String sql) {
    when(configCache.findEnabledJobDefinition("t1", "JOB_A"))
        .thenReturn(JobDefinitionEntity.builder().id(1L).scheduleType("MANUAL").build());
    SchedulePlan plan = new SchedulePlan();
    plan.setPartitionCount(1);
    plan.getPartitions().add(new SchedulePlan.PartitionPlan());
    when(planBuilder.build(any())).thenReturn(plan);
    return service.plan(DryRunPlanRequest.builder()
        .tenantId("t1")
        .jobCode("JOB_A")
        .bizDate(LocalDate.of(2026, Month.MAY, 7))
        .level(DryRunLevel.EXECUTION_PLAN)
        .params(Map.of("sql", sql))
        .build());
  }

  @Test
  @DisplayName("探针语句包含多条语句时拒绝执行, 第二条语句不会下发数据库")
  void shouldRejectMultiStatement_whenProbingSql() {
    DryRunPlanResult result = probeExecutionSql("SELECT 1; DROP TABLE job_definition");

    assertThat(result.findings())
        .extracting(DryRunFinding::code)
        .contains("EXEC_SQL_MULTISTATEMENT_REJECTED")
        .doesNotContain("EXEC_SQL_EXPLAIN_OK");
    // 堆叠语句在进入 EXPLAIN 之前被拒 —— 第二条 DROP 绝不能触达 jdbcTemplate。
    verify(jdbcTemplate, never()).execute(anyString());
  }

  @Test
  @DisplayName("探针语句包含多条查询时同样被拒绝且不下发执行")
  void shouldRejectMultipleSelects_whenProbingSql() {
    DryRunPlanResult result = probeExecutionSql("SELECT 1; SELECT 2");

    assertThat(result.findings())
        .extracting(DryRunFinding::code)
        .contains("EXEC_SQL_MULTISTATEMENT_REJECTED");
    verify(jdbcTemplate, never()).execute(anyString());
  }

  // ── S3: dry-run endpoint reachability 探针 SSRF 出口守卫 ─────────────────────
  private DryRunPlanResult probeExecutionEndpoint(String callbackUrl) {
    when(configCache.findEnabledJobDefinition("t1", "JOB_A"))
        .thenReturn(JobDefinitionEntity.builder().id(1L).scheduleType("MANUAL").build());
    SchedulePlan plan = new SchedulePlan();
    plan.setPartitionCount(1);
    plan.getPartitions().add(new SchedulePlan.PartitionPlan());
    when(planBuilder.build(any())).thenReturn(plan);
    return service.plan(DryRunPlanRequest.builder()
        .tenantId("t1")
        .jobCode("JOB_A")
        .bizDate(LocalDate.of(2026, Month.MAY, 7))
        .level(DryRunLevel.EXECUTION_PLAN)
        .params(Map.of("callbackUrl", callbackUrl))
        .build());
  }

  @Test
  @DisplayName("探针地址指向云元数据地址时判定为拦截, 不做外呼")
  void shouldBlockProbe_whenEndpointIsCloudMetadataAddress() {
    // 169.254.169.254 是 IP literal(无 DNS),出口守卫在 send 前判定 blocked → 绝不外呼。
    DryRunPlanResult result = probeExecutionEndpoint("http://169.254.169.254/latest/meta-data/");

    assertThat(result.findings())
        .extracting(DryRunFinding::code)
        .contains("EXEC_ENDPOINT_BLOCKED")
        .doesNotContain("EXEC_ENDPOINT_OK");
  }

  @Test
  @DisplayName("探针地址指向私网地址时判定为拦截, 不做外呼")
  void shouldBlockProbe_whenEndpointIsPrivateAddress() {
    DryRunPlanResult result = probeExecutionEndpoint("http://10.1.2.3:8080/internal");

    assertThat(result.findings())
        .extracting(DryRunFinding::code)
        .contains("EXEC_ENDPOINT_BLOCKED")
        .doesNotContain("EXEC_ENDPOINT_OK");
  }

  @Test
  @DisplayName("单个地址被拦截后继续探测其余地址并如实汇总")
  void shouldContinueProbing_whenOneEndpointBlocked() {
    when(configCache.findEnabledJobDefinition("t1", "JOB_A"))
        .thenReturn(JobDefinitionEntity.builder().id(1L).scheduleType("MANUAL").build());
    SchedulePlan plan = new SchedulePlan();
    plan.setPartitionCount(1);
    plan.getPartitions().add(new SchedulePlan.PartitionPlan());
    when(planBuilder.build(any())).thenReturn(plan);

    DryRunPlanResult result = service.plan(DryRunPlanRequest.builder()
        .tenantId("t1")
        .jobCode("JOB_A")
        .bizDate(LocalDate.of(2026, Month.MAY, 7))
        .level(DryRunLevel.EXECUTION_PLAN)
        .params(Map.of(
            "callbackUrl", "http://169.254.169.254/latest/meta-data/",
            "endpointUrl", "http://10.1.2.3:8080/internal"))
        .build());

    assertThat(result.findings())
        .extracting(DryRunFinding::code)
        .filteredOn("EXEC_ENDPOINT_BLOCKED"::equals)
        .hasSize(2);
  }

  @Test
  @DisplayName("单条查询探针走执行计划校验并给出通过提示")
  void shouldRunExplain_whenProbingSingleSelect() {
    DryRunPlanResult result = probeExecutionSql("SELECT count(*) FROM batch.job_instance");

    assertThat(result.findings())
        .extracting(DryRunFinding::code)
        .contains("EXEC_SQL_EXPLAIN_OK")
        .doesNotContain("EXEC_SQL_MULTISTATEMENT_REJECTED");
    verify(jdbcTemplate)
        .execute("EXPLAIN (ANALYZE FALSE, COSTS FALSE) SELECT count(*) FROM batch.job_instance");
  }
}
