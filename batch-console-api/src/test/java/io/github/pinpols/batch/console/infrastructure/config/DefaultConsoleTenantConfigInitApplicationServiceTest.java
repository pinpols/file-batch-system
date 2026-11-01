package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileTemplateSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.InitMode;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.config.JobMonitoringDefaultsProperties;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigQueryView;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDependencyRuleRequest;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionView;
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
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionDagInspector;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionWriteSupport;
import io.github.pinpols.batch.console.mapper.ConfigBatchOperationMapper;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * DefaultConsoleTenantConfigInitApplicationService 的单元测试。
 *
 * <p>Covers: multi-tenant fan-out, SKIP_EXISTING vs UPSERT modes, per-item create/skip/update
 * counts, partial tenant failure isolation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("租户配置批量初始化服务: 多租户扇出,跳过与覆盖模式及条目失败统计")
class DefaultConsoleTenantConfigInitApplicationServiceTest {

  @Test
  @DisplayName("退役配置字段在反序列化边界拒绝，不再保留旧Pipeline数据模型")
  void shouldRejectRetiredConfigurationFieldAtJsonBoundary() {
    Assertions.assertThatThrownBy(() -> new ObjectMapper()
            .readValue(
                "{\"targetTenantIds\":[\"t1\"],\"pipelineDefinitions\":[{}]}",
                TenantConfigBatchInitRequest.class))
        .hasMessageContaining("Unsupported configuration field: pipelineDefinitions");
    verify(jobDefinitionMapper, never()).insert(any());
  }

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

  @Mock
  private ConsoleConfigCacheInvalidationService cacheInvalidationService;

  @Mock
  private ConfigBatchOperationMapper operationMapper;

  private DefaultConsoleTenantConfigInitApplicationService service;

  @BeforeEach
  void setUp() {
    TenantDefinitionConfigMappers definitions = new TenantDefinitionConfigMappers(
        jobDefinitionMapper, workflowDefinitionMapper, workflowNodeMapper, workflowEdgeMapper);
    JobMonitoringDefaultsProperties defaults = new JobMonitoringDefaultsProperties();
    WorkflowDefinitionWriteSupport snapshots = new WorkflowDefinitionWriteSupport(
        workflowNodeMapper,
        workflowEdgeMapper,
        workflowDefinitionVersionMapper,
        new ObjectMapper());
    WorkflowDefinitionDagInspector inspector =
        new WorkflowDefinitionDagInspector(jobDefinitionMapper);
    TenantJobDefinitionApplySupport jobs = new TenantJobDefinitionApplySupport(
        jobDefinitionMapper, defaults, singleJobWorkflowProvisioner);
    TenantWorkflowDefinitionApplySupport workflows =
        new TenantWorkflowDefinitionApplySupport(definitions, snapshots, inspector);
    PlatformTransactionManager manager = new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {
        // 无需执行实际操作：配置初始化单元测试只校验回调中的 Mapper 调用。
      }

      @Override
      public void rollback(TransactionStatus status) {
        // 无需执行实际操作：配置初始化单元测试不模拟事务回滚。
      }
    };
    TenantFileConfigApplySupport files = new TenantFileConfigApplySupport(
        fileChannelConfigMapper, fileTemplateConfigMapper, new ObjectMapper());
    TenantOperationalConfigApplySupport operational = new TenantOperationalConfigApplySupport(
        resourceQueueMapper,
        batchWindowMapper,
        businessCalendarMapper,
        calendarHolidayMapper,
        tenantQuotaPolicyMapper,
        alertRoutingConfigMapper);
    TenantConfigInitApplyHandlers handlers =
        new TenantConfigInitApplyHandlers(jobs, manager, files, operational, workflows);
    service = new DefaultConsoleTenantConfigInitApplicationService(
        new TenantConfigInitTenantExecutor(handlers, cacheInvalidationService, operationMapper));
  }

  // ------------------------------------------------------------------ job definitions

  @Test
  @DisplayName("批量更新回传脱敏默认参数时保留原凭据，同时应用普通字段修改")
  void shouldPreserveStoredCredential_whenInitUpsertsMaskedJobDefaults() throws Exception {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.setMode(InitMode.UPSERT);
    request.getJobDefinitions().getFirst().setDefaultParams("""
        {"connection":{"password":"****"},"limit":2}
        """);
    JobDefinitionView existing = existingJobWithDefaults("""
        {"connection":{"password":"stored-credential"},"limit":1}
        """);
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(existing);

    TenantConfigBatchInitResponse response =
        service.batchInit(request, "admin", "masked-job-upsert");

    assertThat(response.results().getFirst().jobDefinitions().updated()).isOne();
    ArgumentCaptor<JobDefinitionMaintenanceUpdateParam> captor =
        ArgumentCaptor.forClass(JobDefinitionMaintenanceUpdateParam.class);
    verify(jobDefinitionMapper).updateJobDefinitionMaintenance(captor.capture());
    JsonNode persisted = new ObjectMapper().readTree(captor.getValue().getDefaultParams());
    assertThat(persisted.path("connection").path("password").asText())
        .isEqualTo("stored-credential");
    assertThat(persisted.path("limit").asInt()).isEqualTo(2);
    assertThat(captor.getValue().isDefaultParamsSpecified()).isTrue();
  }

  @Test
  @DisplayName("批量新建仅有脱敏占位符的作业时拒绝写入并报告条目失败")
  void shouldRejectMaskedCredential_whenInitCreatesJobWithoutStoredValue() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.getJobDefinitions().getFirst().setDefaultParams("""
        {"connection":{"password":"****"}}
        """);

    TenantConfigBatchInitResponse response =
        service.batchInit(request, "admin", "masked-job-create");

    assertThat(response.results().getFirst().jobDefinitions().failed()).isOne();
    assertThat(response.results().getFirst().jobDefinitions().created()).isZero();
    verify(jobDefinitionMapper, never()).insert(any());
  }

  @Test
  @DisplayName("批量更新找不到同位置原凭据时拒绝占位符覆盖")
  void shouldRejectUnresolvedCredential_whenInitUpdatesJobWithoutMatchingValue() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.setMode(InitMode.UPSERT);
    request.getJobDefinitions().getFirst().setDefaultParams("""
        {"connection":{"password":"****"}}
        """);
    JobDefinitionView existing = existingJobWithDefaults("{\"limit\":1}");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(existing);

    TenantConfigBatchInitResponse response =
        service.batchInit(request, "admin", "masked-job-missing");

    assertThat(response.results().getFirst().jobDefinitions().failed()).isOne();
    assertThat(response.results().getFirst().jobDefinitions().updated()).isZero();
    verify(jobDefinitionMapper, never()).updateJobDefinitionMaintenance(any());
  }

  @Test
  @DisplayName("批量更新显式新值时替换原值，普通字段中的星号保持原样")
  void shouldApplyExplicitDefaults_whenInitReplacesCredentialAndOrdinaryMaskText()
      throws Exception {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.setMode(InitMode.UPSERT);
    request.getJobDefinitions().getFirst().setDefaultParams("""
        {"connection":{"password":"replacement-credential"},"label":"****"}
        """);
    JobDefinitionView existing = existingJobWithDefaults("""
        {"connection":{"password":"stored-credential"},"label":"old"}
        """);
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(existing);

    TenantConfigBatchInitResponse response =
        service.batchInit(request, "admin", "explicit-job-defaults");

    assertThat(response.results().getFirst().jobDefinitions().updated()).isOne();
    ArgumentCaptor<JobDefinitionMaintenanceUpdateParam> captor =
        ArgumentCaptor.forClass(JobDefinitionMaintenanceUpdateParam.class);
    verify(jobDefinitionMapper).updateJobDefinitionMaintenance(captor.capture());
    JsonNode persisted = new ObjectMapper().readTree(captor.getValue().getDefaultParams());
    assertThat(persisted.path("connection").path("password").asText())
        .isEqualTo("replacement-credential");
    assertThat(persisted.path("label").asText()).isEqualTo("****");
  }

  @Test
  @DisplayName("批量更新省略默认参数时不指定该列，保留已有参数")
  void shouldKeepDefaultsUnspecified_whenInitOmitsJobDefaults() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.setMode(InitMode.UPSERT);
    JobDefinitionView existing = existingJobWithDefaults("{\"limit\":1}");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(existing);

    TenantConfigBatchInitResponse response =
        service.batchInit(request, "admin", "omitted-job-defaults");

    assertThat(response.results().getFirst().jobDefinitions().updated()).isOne();
    ArgumentCaptor<JobDefinitionMaintenanceUpdateParam> captor =
        ArgumentCaptor.forClass(JobDefinitionMaintenanceUpdateParam.class);
    verify(jobDefinitionMapper).updateJobDefinitionMaintenance(captor.capture());
    assertThat(captor.getValue().isDefaultParamsSpecified()).isFalse();
    assertThat(captor.getValue().getDefaultParams()).isNull();
  }

  private JobDefinitionView existingJobWithDefaults(String defaults) {
    JobDefinitionView existing = new JobDefinitionView();
    existing.setTenantId("t1");
    existing.setJobCode("job-1");
    existing.setScheduleType("MANUAL");
    existing.setDefaultParams(defaults);
    return existing;
  }

  @Test
  @DisplayName("作业定义不存在时,新建并写入上游依赖字段")
  void batchInit_createsJobDefinitionWhenNotExists() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.getJobDefinitions().get(0).setDependsOnJobCode("upstream-job");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);
    JobDefinitionView upstream = new JobDefinitionView();
    upstream.setTenantId("t1");
    upstream.setJobCode("upstream-job");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "upstream-job")).thenReturn(upstream);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.successTenants()).isEqualTo(1);
    assertThat(response.results().get(0).jobDefinitions().created()).isEqualTo(1);
    assertThat(response.results().get(0).jobDefinitions().skipped()).isZero();
    verify(jobDefinitionMapper)
        .insert(argThat(entity -> "upstream-job".equals(entity.getDependsOnJobCode())
            && "t1".equals(entity.getTenantId())));
    verify(jobDefinitionMapper)
        .upsertJobMonitoringPolicy(argThat(policy -> "t1".equals(policy.getTenantId())
            && policy.getSoftRuntimeSeconds() == 0
            && policy.getStartGraceSeconds() == 300));
    verify(cacheInvalidationService).evictAllJobDefinitions("t1");
    verify(cacheInvalidationService).evictAllWorkflowDefinitions("t1");
  }

  @Test
  @DisplayName("批量初始化保存全部上游依赖与逐边业务日对齐")
  void batchInit_persistsMultipleJobDependencyRules() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-downstream", List.of("t1"));
    JobDefinitionSpec spec = request.getJobDefinitions().getFirst();
    List<JobDependencyRuleRequest> dependencyRules = List.of(
        new JobDependencyRuleRequest("job-upstream-a", "PREVIOUS_BIZ_DAY"),
        new JobDependencyRuleRequest("job-upstream-b", "SAME_DAY"));
    spec.setDependencyRules(dependencyRules);
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-downstream")).thenReturn(null);
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-upstream-a"))
        .thenReturn(new JobDefinitionView());
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-upstream-b"))
        .thenReturn(new JobDefinitionView());
    when(jobDefinitionMapper.selectAllDependencies("t1")).thenReturn(List.of());

    TenantConfigBatchInitResponse response =
        service.batchInit(request, "admin", "batch-multiple-dependencies");

    assertThat(response.successTenants()).isOne();
    verify(jobDefinitionMapper)
        .insert(argThat(entity -> "job-upstream-a".equals(entity.getDependsOnJobCode())));
    verify(jobDefinitionMapper).insertDependencyRules("t1", "job-downstream", dependencyRules);
  }

  @Test
  @DisplayName("UPSERT 更新作业定义时,复制执行参数与优先级而不写成 null")
  void batchInit_upsertsAllCopiedJobDefinitionFields() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.setMode(InitMode.UPSERT);
    JobDefinitionSpec spec = request.getJobDefinitions().get(0);
    spec.setTriggerMode("SCHEDULED");
    spec.setExecutionHandler("IMPORT_HANDLER");
    spec.setParamSchema("{\"type\":\"object\"}");
    spec.setPriority(7);

    JobDefinitionView existing = new JobDefinitionView();
    existing.setTenantId("t1");
    existing.setJobCode("job-1");
    existing.setScheduleType("CRON");
    existing.setPriority(2);
    existing.setExecutionHandler("OLD_HANDLER");
    existing.setParamSchema("{\"type\":\"string\"}");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(existing);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "upsert-job-1");

    assertThat(response.results().get(0).jobDefinitions().updated()).isEqualTo(1);
    ArgumentCaptor<JobDefinitionMaintenanceUpdateParam> captor =
        ArgumentCaptor.forClass(JobDefinitionMaintenanceUpdateParam.class);
    verify(jobDefinitionMapper).updateJobDefinitionMaintenance(captor.capture());
    assertThat(captor.getValue().getTriggerMode()).isEqualTo("SCHEDULED");
    assertThat(captor.getValue().getExecutionHandler()).isEqualTo("IMPORT_HANDLER");
    assertThat(captor.getValue().getParamSchema()).isEqualTo("{\"type\":\"object\"}");
    assertThat(captor.getValue().getPriority()).isEqualTo(7);
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
  @DisplayName("跳过模式下作业定义已存在时,计入跳过数且不写库")
  void batchInit_skipsJobDefinitionWhenExistsInSkipMode() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.setMode(InitMode.SKIP_EXISTING);
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1"))
        .thenReturn(new JobDefinitionView());

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
    JobDefinitionView existing = new JobDefinitionView();
    existing.setJobCode("job-1");
    existing.setTenantId("t1");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(existing);
    JobDefinitionView upstream = new JobDefinitionView();
    upstream.setTenantId("t1");
    upstream.setJobCode("next-upstream");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "next-upstream")).thenReturn(upstream);

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
    when(fileChannelConfigMapper.selectByUniqueKey("t1", "ch-1"))
        .thenReturn(FileChannelConfigQueryView.builder().id(1L).build());

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).fileChannels().skipped()).isEqualTo(1);
    verify(fileChannelConfigMapper, never()).upsertFileChannelConfig(any());
  }

  @Test
  @DisplayName("覆盖模式下文件通道已存在时,执行更新并计入更新数")
  void batchInit_upsertsFileChannelInUpsertMode() {
    TenantConfigBatchInitRequest request = requestWithChannel("ch-1", List.of("t1"));
    request.setMode(InitMode.UPSERT);
    when(fileChannelConfigMapper.selectByUniqueKey("t1", "ch-1"))
        .thenReturn(FileChannelConfigQueryView.builder().id(1L).build());

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).fileChannels().updated()).isEqualTo(1);
    verify(fileChannelConfigMapper).upsertFileChannelConfig(any());
  }

  // ------------------------------------------------------------------ file templates

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("租户初始化和演练均拒绝启用的无效定长映射")
  void batchInit_rejectsInvalidFixedWidthMapping(boolean dryRun) {
    TenantConfigBatchInitRequest request = requestWithTemplate("tpl-1", List.of("t1"));
    request.setDryRun(dryRun);
    FileTemplateSpec spec = request.getFileTemplates().getFirst();
    spec.setFileFormatType("FIXED_WIDTH");
    spec.setFieldMappingsJson("[{\"target\":\"name\",\"start\":1.5,\"length\":2}]");

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().getFirst().fileTemplates().failed()).isOne();
    verify(fileTemplateConfigMapper, never()).upsertFileTemplateConfig(any());
  }

  @Test
  @DisplayName("租户初始化允许禁用模板暂存不完整定长映射")
  void batchInit_allowsDisabledFixedWidthDraft() {
    TenantConfigBatchInitRequest request = requestWithTemplate("tpl-1", List.of("t1"));
    FileTemplateSpec spec = request.getFileTemplates().getFirst();
    spec.setEnabled(false);
    spec.setFileFormatType("FIXED_WIDTH");
    spec.setFieldMappingsJson("[{\"target\":\"name\",\"start\":1.5,\"length\":2}]");

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().getFirst().fileTemplates().created()).isOne();
    verify(fileTemplateConfigMapper).upsertFileTemplateConfig(any());
  }

  @Test
  @DisplayName("租户初始化接受有效的定长映射")
  void batchInit_acceptsValidFixedWidthMapping() {
    TenantConfigBatchInitRequest request = requestWithTemplate("tpl-1", List.of("t1"));
    FileTemplateSpec spec = request.getFileTemplates().getFirst();
    spec.setFileFormatType("FIXED_WIDTH");
    spec.setFieldMappingsJson("[{\"target\":\"name\",\"start\":1,\"length\":2}]");

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().getFirst().fileTemplates().created()).isOne();
    verify(fileTemplateConfigMapper).upsertFileTemplateConfig(any());
  }

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
    when(fileTemplateConfigMapper.selectByUniqueKey("t1", "tpl-1", 1))
        .thenReturn(FileTemplateConfigQueryView.builder().id(1L).build());

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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("工作流节点缺少名称时正式初始化和演练均计为失败")
  void batchInit_rejectsWorkflowNodeWithoutName(boolean dryRun) {
    TenantConfigBatchInitRequest request = requestWithWorkflow("wf-1", List.of("t1"));
    request.setDryRun(dryRun);
    WorkflowDefinitionSpec.NodeSpec node = new WorkflowDefinitionSpec.NodeSpec();
    node.setNodeCode("start");
    node.setNodeType("START");
    request.getWorkflowDefinitions().getFirst().setNodes(List.of(node));

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().getFirst().workflowDefinitions().failed()).isOne();
    assertThat(response.results().getFirst().workflowDefinitions().created()).isZero();
    verify(workflowDefinitionMapper, never()).upsertWorkflowDefinition(any());
  }

  @Test
  @DisplayName("跳过现有工作流时不校验不执行的节点配置")
  void batchInit_skipsExistingWorkflowEvenWhenNodeNameIsMissing() {
    TenantConfigBatchInitRequest request = requestWithWorkflow("wf-1", List.of("t1"));
    WorkflowDefinitionSpec.NodeSpec node = new WorkflowDefinitionSpec.NodeSpec();
    node.setNodeCode("start");
    node.setNodeType("START");
    request.getWorkflowDefinitions().getFirst().setNodes(List.of(node));
    when(workflowDefinitionMapper.selectLatestByCode("t1", "wf-1"))
        .thenReturn(new WorkflowDefinitionEntity());

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().getFirst().workflowDefinitions().skipped()).isOne();
    verify(workflowDefinitionMapper, never()).upsertWorkflowDefinition(any());
  }

  @Test
  @DisplayName("有名称的工作流节点可通过演练")
  void batchInit_acceptsWorkflowNodeWithName_whenDryRun() {
    TenantConfigBatchInitRequest request = requestWithWorkflow("wf-1", List.of("t1"));
    request.setDryRun(true);
    WorkflowDefinitionSpec.NodeSpec node = new WorkflowDefinitionSpec.NodeSpec();
    node.setNodeCode("start");
    node.setNodeName("Start");
    node.setNodeType("START");
    request.getWorkflowDefinitions().getFirst().setNodes(List.of(node));

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().getFirst().workflowDefinitions().created()).isOne();
    verify(workflowDefinitionMapper, never()).upsertWorkflowDefinition(any());
  }

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
    JobDefinitionView managedJob = new JobDefinitionView();
    managedJob.setId(51L);
    managedJob.setJobCode("job-1");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(managedJob);
    when(workflowDefinitionMapper.selectLatestByCode("t1", "wf-1")).thenReturn(null);
    // 执行 upsert 后，模拟已保存的实体。
    WorkflowDefinitionEntity saved = new WorkflowDefinitionEntity();
    saved.setId(10L);
    saved.setWorkflowCode("wf-1");
    when(workflowDefinitionMapper.upsertWorkflowDefinition(any())).thenReturn(1);
    when(workflowDefinitionMapper.selectByUniqueKey("t1", "wf-1", 1))
        .thenReturn(saved); // 创建后按精确版本读取刚保存的定义。

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
    when(workflowDefinitionMapper.selectLatestByCode("t1", "wf-1")).thenReturn(existing);

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
    JobDefinitionView managedJob = new JobDefinitionView();
    managedJob.setId(70L);
    managedJob.setJobCode("JOB_A");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "JOB_A")).thenReturn(managedJob);
    when(workflowDefinitionMapper.selectById("t1", 21L)).thenReturn(existing);
    when(workflowDefinitionMapper.selectBySingleJobDefinitionId("t1", 70L)).thenReturn(existing);
    when(workflowDefinitionMapper.upsertWorkflowDefinition(any())).thenReturn(1);
    when(workflowDefinitionMapper.updateAndBumpVersion(argThat(value -> value.tenantId() != null)))
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
        .updateAndBumpVersion(argThat(value -> Objects.equals(value.tenantId(), "t1")
            && Objects.equals(value.id(), 21L)
            && Objects.equals(value.expectedVersion(), 3)
            && Objects.equals(value.workflowName(), "Existing workflow")
            && Objects.equals(value.workflowType(), "DAG")
            && Objects.equals(value.enabled(), true)
            && Objects.equals(value.scheduleEnabled(), false)
            && Objects.equals(value.scheduleType(), "MANUAL")
            && value.scheduleExpr() == null
            && Objects.equals(value.timezone(), "Asia/Shanghai")
            && Objects.equals(value.triggerMode(), "MANUAL")
            && value.calendarCode() == null
            && value.dependsOnJobCode() == null));
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
    verify(cacheInvalidationService, never()).evictAllJobDefinitions(anyString());
  }

  @Test
  @DisplayName("试运行不会补建缺失的托管单作业工作流")
  void batchInit_dryRunDoesNotProvisionManagedWorkflow() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-existing", List.of("t1"));
    request.setDryRun(true);
    JobDefinitionView existing = new JobDefinitionView();
    existing.setTenantId("t1");
    existing.setJobCode("job-existing");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-existing")).thenReturn(existing);

    service.batchInit(request, "admin", "batch-dry-managed-workflow");

    verify(singleJobWorkflowProvisioner, never()).provision(any());
    verify(cacheInvalidationService, never()).evictAllWorkflowDefinitions(anyString());
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
