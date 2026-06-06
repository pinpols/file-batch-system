package com.example.batch.orchestrator.application.service.dryrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.config.S3StorageProperties;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.application.plan.SchedulePlanBuilder;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.WorkflowEdgeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

class DefaultDryRunPlanServiceTest {

  private OrchestratorConfigCacheService configCache;
  private SchedulePlanBuilder planBuilder;
  private DefaultDryRunPlanService service;

  @BeforeEach
  void setUp() {
    configCache = mock(OrchestratorConfigCacheService.class);
    planBuilder = mock(SchedulePlanBuilder.class);
    WorkflowNodeMapper nodeMapper = mock(WorkflowNodeMapper.class);
    WorkflowEdgeMapper edgeMapper = mock(WorkflowEdgeMapper.class);
    BatchTimezoneProvider tz = new BatchTimezoneProvider(new BatchTimezoneProperties());
    @SuppressWarnings("unchecked")
    ObjectProvider<JdbcTemplate> jdbcTemplateProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<S3Client> s3ClientProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<S3StorageProperties> minioPropsProvider = mock(ObjectProvider.class);
    service =
        new DefaultDryRunPlanService(
            configCache,
            planBuilder,
            nodeMapper,
            edgeMapper,
            tz,
            jdbcTemplateProvider,
            s3ClientProvider,
            minioPropsProvider);
  }

  @Test
  void l1ReportsErrorWhenJobDefinitionMissing() {
    when(configCache.findEnabledJobDefinition("t1", "JOB_A")).thenReturn(null);
    when(configCache.findEnabledWorkflowDefinition("t1", "JOB_A")).thenReturn(null);

    DryRunPlanResult result =
        service.plan(
            DryRunPlanRequest.builder()
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
  void l1PassesWhenCronExpressionValid() {
    when(configCache.findEnabledJobDefinition("t1", "JOB_A"))
        .thenReturn(
            JobDefinitionEntity.builder()
                .id(1L)
                .tenantId("t1")
                .jobCode("JOB_A")
                .scheduleType("CRON")
                .scheduleExpr("0 0 * * * ?")
                .timezone("Asia/Shanghai")
                .build());

    DryRunPlanResult result =
        service.plan(
            DryRunPlanRequest.builder()
                .tenantId("t1")
                .jobCode("JOB_A")
                .level(DryRunLevel.CONFIG_VALIDATE)
                .build());

    assertThat(result.success()).isTrue();
    assertThat(result.findings()).extracting(DryRunFinding::code).contains("JOB_CRON_OK");
  }

  @Test
  void l1ReportsErrorWhenCronExpressionInvalid() {
    when(configCache.findEnabledJobDefinition("t1", "JOB_A"))
        .thenReturn(
            JobDefinitionEntity.builder()
                .id(1L)
                .tenantId("t1")
                .jobCode("JOB_A")
                .scheduleType("CRON")
                .scheduleExpr("invalid cron")
                .timezone("Asia/Shanghai")
                .build());

    DryRunPlanResult result =
        service.plan(
            DryRunPlanRequest.builder()
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
  void l2ErrorsWhenBizDateMissing() {
    DryRunPlanResult result =
        service.plan(
            DryRunPlanRequest.builder()
                .tenantId("t1")
                .jobCode("JOB_A")
                .level(DryRunLevel.SCHEDULE_PLAN)
                .build());

    assertThat(result.success()).isFalse();
    assertThat(result.findings()).extracting(DryRunFinding::code).contains("BIZDATE_MISSING");
  }

  @Test
  void l2EmitsScheduleSummary() {
    when(configCache.findEnabledJobDefinition("t1", "JOB_A"))
        .thenReturn(
            JobDefinitionEntity.builder()
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

    DryRunPlanResult result =
        service.plan(
            DryRunPlanRequest.builder()
                .tenantId("t1")
                .jobCode("JOB_A")
                .bizDate(LocalDate.of(2026, 5, 7))
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
  void l3InheritsL2AndAddsExecutionStub() {
    when(configCache.findEnabledJobDefinition("t1", "JOB_A"))
        .thenReturn(JobDefinitionEntity.builder().id(1L).scheduleType("MANUAL").build());
    SchedulePlan plan = new SchedulePlan();
    plan.setPartitionCount(1);
    plan.getPartitions().add(new SchedulePlan.PartitionPlan());
    when(planBuilder.build(any())).thenReturn(plan);

    DryRunPlanResult result =
        service.plan(
            DryRunPlanRequest.builder()
                .tenantId("t1")
                .jobCode("JOB_A")
                .bizDate(LocalDate.of(2026, 5, 7))
                .level(DryRunLevel.EXECUTION_PLAN)
                .build());

    assertThat(result.success()).isTrue();
    // L3 真接后：无 SQL/MinIO/endpoint params 时返回 EXEC_PLAN_NO_PROBES_TRIGGERED
    assertThat(result.findings())
        .extracting(DryRunFinding::code)
        .contains("EXEC_PLAN_NO_PROBES_TRIGGERED");
    assertThat(result.summary())
        .containsEntry("l3SqlProbed", 0)
        .containsEntry("l3MinioProbed", 0)
        .containsEntry("l3EndpointProbed", 0);
  }

  @Test
  void l1RequiresParamsWhenSchemaSaysSo() {
    when(configCache.findEnabledJobDefinition("t1", "JOB_A"))
        .thenReturn(
            JobDefinitionEntity.builder()
                .id(1L)
                .scheduleType("MANUAL")
                .paramSchema(Map.of("required", List.of("targetTable")))
                .build());

    DryRunPlanResult result =
        service.plan(
            DryRunPlanRequest.builder()
                .tenantId("t1")
                .jobCode("JOB_A")
                .level(DryRunLevel.CONFIG_VALIDATE)
                .build());

    assertThat(result.success()).isFalse();
    assertThat(result.findings()).extracting(DryRunFinding::code).contains("JOB_PARAMS_MISSING");
  }
}
