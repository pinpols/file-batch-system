package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileTemplateSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.InitMode;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.PipelineDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.config.JobMonitoringDefaultsProperties;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertRoutingConfigMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionVersionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionInsertParam;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
import io.github.pinpols.batch.console.infrastructure.workflow.DefaultSingleJobWorkflowProvisioner;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionWriteSupport;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Unit tests for DefaultConsoleTenantConfigInitApplicationService.
 *
 * <p>Covers: multi-tenant fan-out, SKIP_EXISTING vs UPSERT modes, per-item create/skip/update
 * counts, partial tenant failure isolation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("租户配置批量初始化服务: 多租户扇出,跳过与覆盖模式及条目失败统计")
class DefaultConsoleTenantConfigInitApplicationServiceTest {

  @Test
  @DisplayName("租户执行器方法带有事务注解,保证按租户划分事务边界")
  void shouldKeepPerTenantBoundary_whenExecutorMethodIsAnnotated() throws Exception {
    Method execute = TenantConfigInitTenantExecutor.class.getDeclaredMethod(
        "execute", String.class, TenantConfigBatchInitRequest.class, String.class, boolean.class);

    assertThat(execute.getAnnotation(Transactional.class)).isNotNull();
  }

  @Mock
  private JobDefinitionMapper jobDefinitionMapper;

  @Mock
  private WorkflowDefinitionMapper workflowDefinitionMapper;

  @Mock
  private WorkflowNodeMapper workflowNodeMapper;

  @Mock
  private WorkflowEdgeMapper workflowEdgeMapper;

  @Mock
  private WorkflowDefinitionVersionMapper workflowDefinitionVersionMapper;

  @Mock
  private DefaultSingleJobWorkflowProvisioner singleJobWorkflowProvisioner;

  @Mock
  private FileChannelConfigMapper fileChannelConfigMapper;

  @Mock
  private FileTemplateConfigMapper fileTemplateConfigMapper;

  @Mock
  private ResourceQueueMapper resourceQueueMapper;

  @Mock
  private BatchWindowMapper batchWindowMapper;

  @Mock
  private BusinessCalendarMapper businessCalendarMapper;

  @Mock
  private CalendarHolidayMapper calendarHolidayMapper;

  @Mock
  private TenantQuotaPolicyMapper tenantQuotaPolicyMapper;

  @Mock
  private AlertRoutingConfigMapper alertRoutingConfigMapper;

  private DefaultConsoleTenantConfigInitApplicationService service;

  @BeforeEach
  void setUp() {
    TenantConfigInitApplyHandlers handlers = new TenantConfigInitApplyHandlers(
        new TenantDefinitionConfigMappers(
            jobDefinitionMapper, workflowDefinitionMapper, workflowNodeMapper, workflowEdgeMapper),
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
          }

          @Override
          public void commit(TransactionStatus status) {
            // no-op: config-init unit tests only verify mapper calls inside the callback.
          }

          @Override
          public void rollback(TransactionStatus status) {
            // no-op: config-init unit tests do not simulate transaction rollback.
          }
        },
        new TenantFileConfigApplySupport(fileChannelConfigMapper, fileTemplateConfigMapper),
        new TenantOperationalConfigApplySupport(
            resourceQueueMapper,
            batchWindowMapper,
            businessCalendarMapper,
            calendarHolidayMapper,
            tenantQuotaPolicyMapper,
            alertRoutingConfigMapper),
        new JobMonitoringDefaultsProperties(),
        new WorkflowDefinitionWriteSupport(
            workflowNodeMapper,
            workflowEdgeMapper,
            workflowDefinitionVersionMapper,
            new ObjectMapper()),
        singleJobWorkflowProvisioner);
    service = new DefaultConsoleTenantConfigInitApplicationService(
        new TenantConfigInitTenantExecutor(handlers));
  }

  // ------------------------------------------------------------------ job definitions

  @Test
  @DisplayName("作业定义不存在时,新建并写入上游依赖字段")
  void batchInit_createsJobDefinitionWhenNotExists() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.getJobDefinitions().get(0).setDependsOnJobCode("upstream-job");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.successTenants()).isEqualTo(1);
    assertThat(response.results().get(0).jobDefinitions().created()).isEqualTo(1);
    assertThat(response.results().get(0).jobDefinitions().skipped()).isZero();
    verify(jobDefinitionMapper)
        .insert(argThat(entity -> "upstream-job".equals(entity.getDependsOnJobCode())
            && "t1".equals(entity.getTenantId())
            && entity.getSoftRuntimeSeconds() == 0
            && entity.getStartGraceSeconds() == 300));
  }

  @Test
  @DisplayName("请求携带增量执行模式与水位字段时,原样透传到落库实体")
  void batchInit_passesThroughExecutionModeAndWatermarkField() {
    // 回归:bundle/init 的 JobDefinitionSpec 曾漏 executionMode/watermarkField,
    // 致向导填 INCREMENTAL + 水位字段被静默丢弃、落库退化为 FULL。
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.getJobDefinitions().get(0).setExecutionMode("INCREMENTAL");
    request.getJobDefinitions().get(0).setWatermarkField("updated_at");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);

    service.batchInit(request, "admin", "batch-test-inc");

    verify(jobDefinitionMapper)
        .insert(argThat(entity -> "INCREMENTAL".equals(entity.getExecutionMode())
            && "updated_at".equals(entity.getWatermarkField())));
  }

  @Test
  @DisplayName("规格未指定执行模式时,默认按全量模式落库")
  void batchInit_defaultsExecutionModeToFullWhenSpecOmitsIt() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);

    service.batchInit(request, "admin", "batch-test-full");

    verify(jobDefinitionMapper).insert(argThat(entity -> "FULL".equals(entity.getExecutionMode())));
  }

  @Test
  @DisplayName("增量执行模式缺少水位字段时,直接拒绝且不写库")
  void batchInit_rejectsIncrementalWithoutWatermark() {
    // 回归:INCREMENTAL 缺 watermarkField 的 spec 应被拒(不 insert),避免建出跑不起来的增量作业。
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.getJobDefinitions().get(0).setExecutionMode("INCREMENTAL");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);

    service.batchInit(request, "admin", "batch-test-inc-nowm");

    verify(jobDefinitionMapper, never()).insert(any());
  }

  @Test
  @DisplayName("旧 Pipeline 初始化只迁移业务参数到 Job，不再写租户 Pipeline 定义")
  void batchInit_migratesLegacyPipelineParametersIntoJobDefaults() {
    TenantConfigBatchInitRequest request = requestWithJobDef("legacy-import", List.of("t1"));
    request.getJobDefinitions().get(0).setJobType("IMPORT");
    PipelineDefinitionSpec pipeline = new PipelineDefinitionSpec();
    pipeline.setJobCode("legacy-import");
    pipeline.setPipelineType("IMPORT");
    pipeline.setVersion(4);
    pipeline.setEnabled(true);
    List<PipelineDefinitionSpec.StepSpec> steps = new java.util.ArrayList<>();
    String[] stages = {"RECEIVE", "PREPROCESS", "PARSE", "VALIDATE", "LOAD", "FEEDBACK"};
    for (int index = 0; index < stages.length; index++) {
      PipelineDefinitionSpec.StepSpec step = new PipelineDefinitionSpec.StepSpec();
      step.setStageCode(stages[index]);
      step.setStepOrder(index + 1);
      step.setStepParams(index == 0 ? "{\"businessOption\":\"kept\"}" : "{}");
      step.setTimeoutSeconds(0);
      step.setRetryPolicy("NONE");
      step.setRetryMaxCount(0);
      step.setEnabled(true);
      steps.add(step);
    }
    pipeline.setSteps(steps);
    request.setPipelineDefinitions(List.of(pipeline));
    when(jobDefinitionMapper.selectByUniqueKey("t1", "legacy-import")).thenReturn(null);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-migrate");

    assertThat(response.results().getFirst().pipelineDefinitions().migrated()).isEqualTo(1);
    assertThat(response.results().getFirst().pipelineDefinitions().failed()).isZero();
    verify(jobDefinitionMapper)
        .insert(argThat(job -> job.getDefaultParams() != null
            && job.getDefaultParams().contains("systemPipelineStepParams")
            && job.getDefaultParams().contains("businessOption")
            && job.getDefaultParams().contains("kept")));
  }

  @Test
  @DisplayName("旧 Pipeline 初始化遇到类型不匹配时失败关闭且不迁移参数")
  void batchInit_rejectsLegacyPipelineTypeMismatch() {
    TenantConfigBatchInitRequest request = requestWithJobDef("legacy-import", List.of("t1"));
    request.getJobDefinitions().get(0).setJobType("IMPORT");
    PipelineDefinitionSpec pipeline = new PipelineDefinitionSpec();
    pipeline.setJobCode("legacy-import");
    pipeline.setPipelineType("EXPORT");
    pipeline.setEnabled(true);
    request.setPipelineDefinitions(List.of(pipeline));
    when(jobDefinitionMapper.selectByUniqueKey("t1", "legacy-import")).thenReturn(null);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-invalid");

    assertThat(response.results().getFirst().pipelineDefinitions().failed()).isEqualTo(1);
    assertThat(response.results().getFirst().jobDefinitions().created()).isZero();
    verify(jobDefinitionMapper, never()).insert(any());
  }

  @Test
  @DisplayName("跳过模式下作业定义已存在时,计入跳过数且不写库")
  void batchInit_skipsJobDefinitionWhenExistsInSkipMode() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.setMode(InitMode.SKIP_EXISTING);
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1"))
        .thenReturn(new JobDefinitionEntity());

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).jobDefinitions().skipped()).isEqualTo(1);
    assertThat(response.results().get(0).jobDefinitions().created()).isZero();
    verify(jobDefinitionMapper, never()).insert(any());
  }

  @Test
  @DisplayName("覆盖模式下作业定义已存在时,更新维护字段并计入更新数")
  void batchInit_updatesJobDefinitionWhenExistsInUpsertMode() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.getJobDefinitions().get(0).setDependsOnJobCode("next-upstream");
    request.setMode(InitMode.UPSERT);
    JobDefinitionEntity existing = new JobDefinitionEntity();
    existing.setJobCode("job-1");
    existing.setTenantId("t1");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(existing);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).jobDefinitions().updated()).isEqualTo(1);
    verify(jobDefinitionMapper)
        .updateJobDefinitionMaintenance(
            argThat(param -> "next-upstream".equals(param.getDependsOnJobCode())));
  }

  // ------------------------------------------------------------------ file channels

  @Test
  @DisplayName("文件通道不存在时,新建并计入创建数")
  void batchInit_createsFileChannelWhenNotExists() {
    TenantConfigBatchInitRequest request = requestWithChannel("ch-1", List.of("t1"));
    when(fileChannelConfigMapper.selectByUniqueKey("t1", "ch-1")).thenReturn(null);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).fileChannels().created()).isEqualTo(1);
    verify(fileChannelConfigMapper).upsertFileChannelConfig(any());
  }

  @Test
  @DisplayName("跳过模式下文件通道已存在时,计入跳过数且不写库")
  void batchInit_skipsFileChannelWhenExistsInSkipMode() {
    TenantConfigBatchInitRequest request = requestWithChannel("ch-1", List.of("t1"));
    when(fileChannelConfigMapper.selectByUniqueKey("t1", "ch-1")).thenReturn(Map.of("id", 1));

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).fileChannels().skipped()).isEqualTo(1);
    verify(fileChannelConfigMapper, never()).upsertFileChannelConfig(any());
  }

  @Test
  @DisplayName("覆盖模式下文件通道已存在时,执行更新并计入更新数")
  void batchInit_upsertsFileChannelInUpsertMode() {
    TenantConfigBatchInitRequest request = requestWithChannel("ch-1", List.of("t1"));
    request.setMode(InitMode.UPSERT);
    when(fileChannelConfigMapper.selectByUniqueKey("t1", "ch-1")).thenReturn(Map.of("id", 1));

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).fileChannels().updated()).isEqualTo(1);
    verify(fileChannelConfigMapper).upsertFileChannelConfig(any());
  }

  // ------------------------------------------------------------------ file templates

  @Test
  @DisplayName("文件模板不存在时,新建并计入创建数")
  void batchInit_createsFileTemplateWhenNotExists() {
    TenantConfigBatchInitRequest request = requestWithTemplate("tpl-1", List.of("t1"));
    when(fileTemplateConfigMapper.selectByUniqueKey("t1", "tpl-1", 1)).thenReturn(null);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).fileTemplates().created()).isEqualTo(1);
    verify(fileTemplateConfigMapper).upsertFileTemplateConfig(any());
  }

  @Test
  @DisplayName("跳过模式下文件模板已存在时,计入跳过数且不写库")
  void batchInit_skipsFileTemplateWhenExistsInSkipMode() {
    TenantConfigBatchInitRequest request = requestWithTemplate("tpl-1", List.of("t1"));
    when(fileTemplateConfigMapper.selectByUniqueKey("t1", "tpl-1", 1)).thenReturn(Map.of("id", 1));

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).fileTemplates().skipped()).isEqualTo(1);
    verify(fileTemplateConfigMapper, never()).upsertFileTemplateConfig(any());
  }

  // ------------------------------------------------------------------ multi-tenant fan-out

  @Test
  @DisplayName("批量初始化覆盖多个目标租户时,每个租户都写入作业定义")
  void batchInit_appliesConfigToAllTargetTenants() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1", "t2", "t3"));
    when(jobDefinitionMapper.selectByUniqueKey(anyString(), eq("job-1"))).thenReturn(null);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.totalTenants()).isEqualTo(3);
    assertThat(response.successTenants()).isEqualTo(3);
    verify(jobDefinitionMapper, times(3)).insert(any(JobDefinitionEntity.class));
  }

  @Test
  @DisplayName("单个条目失败只计入失败数,该租户整体仍判定成功")
  void batchInit_itemFailureTrackedInStatsBothTenantsStillSuccess() {
    // Per-item exceptions are caught at item level → tracked as failed count, tenant = success
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1", "t2"));
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);
    when(jobDefinitionMapper.selectByUniqueKey("t2", "job-1")).thenReturn(null);
    when(jobDefinitionMapper.insert(any())).thenAnswer(inv -> {
      JobDefinitionEntity e = inv.getArgument(0);
      if ("t2".equals(e.getTenantId())) {
        throw new RuntimeException("DB error for t2");
      }
      return 1;
    });

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    // t1 succeeds with 1 created; t2 has 1 failed item but tenant-level success=true
    assertThat(response.totalTenants()).isEqualTo(2);
    TenantConfigBatchInitResponse.TenantInitResult t1Result = response.results().stream()
        .filter(r -> "t1".equals(r.tenantId()))
        .findFirst()
        .orElseThrow();
    assertThat(t1Result.success()).isTrue();
    assertThat(t1Result.jobDefinitions().created()).isEqualTo(1);

    TenantConfigBatchInitResponse.TenantInitResult t2Result = response.results().stream()
        .filter(r -> "t2".equals(r.tenantId()))
        .findFirst()
        .orElseThrow();
    assertThat(t2Result.success()).isTrue();
    assertThat(t2Result.jobDefinitions().failed()).isEqualTo(1);
  }

  @Test
  @DisplayName("严格模式下条目失败时,整体回滚并标记该租户失败")
  void batchInit_strictModeRollsBackOnItemFailure() {
    // strict=true (Job Bundle 路径): 任一 spec failed 即整体回滚,tenant 结果标 failed
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.setStrict(true);
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);
    when(jobDefinitionMapper.insert(any(JobDefinitionEntity.class)))
        .thenThrow(new RuntimeException("simulated DB error"));

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.totalTenants()).isEqualTo(1);
    assertThat(response.failureTenants()).isEqualTo(1);
    TenantConfigBatchInitResponse.TenantInitResult t1Result = response.results().get(0);
    assertThat(t1Result.success()).isFalse();
    assertThat(t1Result.errorMessage()).contains("strict bundle aborted");
  }

  @Test
  @DisplayName("配置列表为空时,各租户仍返回成功且计数全部为零")
  void batchInit_handlesEmptyConfigLists() {
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of("t1", "t2"));

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.successTenants()).isEqualTo(2);
    assertThat(response.results()).hasSize(2);
    response.results().forEach(r -> {
      assertThat(r.success()).isTrue();
      assertThat(r.jobDefinitions().created()).isZero();
      assertThat(r.fileChannels().created()).isZero();
    });
  }

  // ------------------------------------------------------------------ workflow definitions

  @Test
  @DisplayName("工作流定义不存在时,新建并计入创建数")
  void batchInit_createsWorkflowWhenNotExists() {
    TenantConfigBatchInitRequest request = requestWithWorkflow("wf-1", List.of("t1"));
    lenient()
        .when(workflowNodeMapper.selectByQuery(any(WorkflowNodeQuery.class)))
        .thenReturn(List.of());
    lenient()
        .when(workflowEdgeMapper.selectByQuery(any(WorkflowEdgeQuery.class)))
        .thenReturn(List.of());
    WorkflowDefinitionSpec spec = request.getWorkflowDefinitions().get(0);
    spec.setScheduleType("CRON");
    spec.setScheduleExpr("0 0 2 * * *");
    spec.setTimezone("Asia/Shanghai");
    spec.setTriggerMode("SCHEDULED");
    spec.setScheduleEnabled(true);
    spec.setSingleJobCode("job-1");
    JobDefinitionEntity managedJob = new JobDefinitionEntity();
    managedJob.setId(51L);
    managedJob.setJobCode("job-1");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(managedJob);
    when(workflowDefinitionMapper.selectByQuery(any())).thenReturn(List.of());
    when(workflowDefinitionMapper.selectByUniqueKey("t1", "wf-1", 1)).thenReturn(null);
    // After upsert, simulate saved entity
    WorkflowDefinitionEntity saved = new WorkflowDefinitionEntity();
    saved.setId(10L);
    saved.setWorkflowCode("wf-1");
    when(workflowDefinitionMapper.upsertWorkflowDefinition(any())).thenReturn(1);
    when(workflowDefinitionMapper.selectByUniqueKey("t1", "wf-1", 1))
        .thenReturn(null) // first call (check existence) = not found
        .thenReturn(saved); // second call (after upsert) = found

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).workflowDefinitions().created()).isEqualTo(1);
    ArgumentCaptor<WorkflowDefinitionUpsertParam> paramCaptor =
        ArgumentCaptor.forClass(WorkflowDefinitionUpsertParam.class);
    verify(workflowDefinitionMapper).upsertWorkflowDefinition(paramCaptor.capture());
    assertThat(paramCaptor.getValue().getScheduleType()).isEqualTo("CRON");
    assertThat(paramCaptor.getValue().getScheduleExpr()).isEqualTo("0 0 2 * * *");
    assertThat(paramCaptor.getValue().getScheduleEnabled()).isTrue();
    assertThat(paramCaptor.getValue().getSingleJobDefinitionId()).isEqualTo(51L);
  }

  @Test
  @DisplayName("跳过模式下工作流定义已存在时,计入跳过数且不写库")
  void batchInit_skipsWorkflowWhenExistsInSkipMode() {
    TenantConfigBatchInitRequest request = requestWithWorkflow("wf-1", List.of("t1"));
    WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity();
    existing.setId(1L);
    when(workflowDefinitionMapper.selectByQuery(any())).thenReturn(List.of(existing));

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).workflowDefinitions().skipped()).isEqualTo(1);
    verify(workflowDefinitionMapper, never()).upsertWorkflowDefinition(any());
  }

  @Test
  @DisplayName("覆盖工作流定义时以目标版本CAS递增并保存调度快照")
  void batchInit_updatesWorkflowVersionAndSnapshotInUpsertMode() {
    TenantConfigBatchInitRequest request = requestWithWorkflow("wf-1", List.of("t1"));
    request.setMode(InitMode.UPSERT);
    request.getWorkflowDefinitions().getFirst().setSingleJobCode("JOB_A");
    WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity();
    existing.setId(21L);
    existing.setWorkflowCode("TARGET-WF-55");
    existing.setWorkflowName("Existing workflow");
    existing.setWorkflowType("DAG");
    existing.setVersion(3);
    existing.setEnabled(true);
    JobDefinitionEntity managedJob = new JobDefinitionEntity();
    managedJob.setId(70L);
    managedJob.setJobCode("JOB_A");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "JOB_A")).thenReturn(managedJob);
    when(workflowDefinitionMapper.selectById("t1", 21L)).thenReturn(existing);
    when(workflowDefinitionMapper.selectBySingleJobDefinitionId("t1", 70L)).thenReturn(existing);
    when(workflowDefinitionMapper.upsertWorkflowDefinition(any())).thenReturn(1);
    when(workflowDefinitionMapper.updateAndBumpVersion(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-upsert");

    assertThat(response.results().getFirst().workflowDefinitions().updated()).isEqualTo(1);
    ArgumentCaptor<WorkflowDefinitionVersionInsertParam> snapshotCaptor =
        ArgumentCaptor.forClass(WorkflowDefinitionVersionInsertParam.class);
    verify(workflowDefinitionVersionMapper).insertVersionSnapshot(snapshotCaptor.capture());
    assertThat(snapshotCaptor.getValue().getVersion()).isEqualTo(4);
    assertThat(snapshotCaptor.getValue().getWorkflowCode()).isEqualTo("TARGET-WF-55");
    ArgumentCaptor<WorkflowDefinitionUpsertParam> upsertCaptor =
        ArgumentCaptor.forClass(WorkflowDefinitionUpsertParam.class);
    verify(workflowDefinitionMapper).upsertWorkflowDefinition(upsertCaptor.capture());
    assertThat(upsertCaptor.getValue().getWorkflowCode()).isEqualTo("TARGET-WF-55");
    assertThat(upsertCaptor.getValue().getSingleJobDefinitionId()).isEqualTo(70L);
    verify(workflowDefinitionMapper)
        .updateAndBumpVersion(
            eq("t1"),
            eq(21L),
            eq(3),
            eq("Existing workflow"),
            eq("DAG"),
            eq(true),
            eq(false),
            eq("MANUAL"),
            isNull(),
            eq("Asia/Shanghai"),
            eq("MANUAL"),
            isNull(),
            isNull());
  }

  // ------------------------------------------------------------------ dry-run & batchOperationId

  @Test
  @DisplayName("试运行模式下只统计不做写入,并回传本次批次操作号")
  void batchInit_dryRunSkipsAllWrites() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.setDryRun(true);
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-dry-001");

    assertThat(response.dryRun()).isTrue();
    assertThat(response.batchOperationId()).isEqualTo("batch-dry-001");
    assertThat(response.results().get(0).jobDefinitions().created()).isEqualTo(1);
    verify(jobDefinitionMapper, never()).insert(any());
  }

  @Test
  @DisplayName("批量初始化结束后,响应回传批次操作号且标记为非试运行")
  void batchInit_returnsBatchOperationId() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-abc-123");

    assertThat(response.batchOperationId()).isEqualTo("batch-abc-123");
    assertThat(response.dryRun()).isFalse();
  }

  // ------------------------------------------------------------------ helpers

  private TenantConfigBatchInitRequest requestWithJobDef(String jobCode, List<String> tenants) {
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(tenants);
    JobDefinitionSpec spec = new JobDefinitionSpec();
    spec.setJobCode(jobCode);
    spec.setJobType("BATCH");
    spec.setScheduleType("CRON");
    spec.setEnabled(true);
    request.setJobDefinitions(List.of(spec));
    return request;
  }

  private TenantConfigBatchInitRequest requestWithChannel(
      String channelCode, List<String> tenants) {
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(tenants);
    FileChannelSpec spec = new FileChannelSpec();
    spec.setChannelCode(channelCode);
    spec.setChannelType("SFTP");
    spec.setEnabled(true);
    request.setFileChannels(List.of(spec));
    return request;
  }

  private TenantConfigBatchInitRequest requestWithTemplate(
      String templateCode, List<String> tenants) {
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(tenants);
    FileTemplateSpec spec = new FileTemplateSpec();
    spec.setTemplateCode(templateCode);
    spec.setTemplateType("IMPORT");
    spec.setVersion(1);
    spec.setEnabled(true);
    request.setFileTemplates(List.of(spec));
    return request;
  }

  private TenantConfigBatchInitRequest requestWithWorkflow(
      String workflowCode, List<String> tenants) {
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(tenants);
    WorkflowDefinitionSpec spec = new WorkflowDefinitionSpec();
    spec.setWorkflowCode(workflowCode);
    spec.setEnabled(true);
    request.setWorkflowDefinitions(List.of(spec));
    return request;
  }
}
