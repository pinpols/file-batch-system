package io.github.pinpols.batch.console.infrastructure.config;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_COMPLETION_DEADLINE_DAY_OFFSET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_COMPLETION_DEADLINE_LOCAL_TIME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_COMPLETION_DEADLINE_SEVERITY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_DEPENDENCY_COMPLETION_WINDOW_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_JOB_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_JOB_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_SOFT_RUNTIME_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_SOFT_RUNTIME_SEVERITY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_START_GRACE_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_START_GRACE_SEVERITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowMonitoringPolicyEntity;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.config.JobMonitoringDefaultsProperties;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.param.FileChannelConfigUpsertParam;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigQueryView;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.JobMonitoringPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionView;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionVersionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowMonitoringPolicyMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionInsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionUpdateParam;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PackageValidationResult;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SheetResult;
import io.github.pinpols.batch.console.infrastructure.workflow.DefaultSingleJobWorkflowProvisioner;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionWriteSupport;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("配置包作业监控策略导入:默认值、时限解析与作业引用校验")
@ExtendWith(MockitoExtension.class)
class TenantConfigPackageExcelMonitoringPolicyApplyTest {

  @Mock
  private ResourceQueueMapper resourceQueueMapper;

  @Mock
  private BusinessCalendarMapper businessCalendarMapper;

  @Mock
  private CalendarHolidayMapper calendarHolidayMapper;

  @Mock
  private BatchWindowMapper batchWindowMapper;

  @Mock
  private FileChannelConfigMapper fileChannelConfigMapper;

  @Mock
  private FileTemplateConfigMapper fileTemplateConfigMapper;

  @Mock
  private JobDefinitionMapper jobDefinitionMapper;

  @Mock
  private WorkflowDefinitionMapper workflowDefinitionMapper;

  @Mock
  private WorkflowMonitoringPolicyMapper workflowMonitoringPolicyMapper;

  @Mock
  private WorkflowNodeMapper workflowNodeMapper;

  @Mock
  private WorkflowEdgeMapper workflowEdgeMapper;

  @Mock
  private WorkflowDefinitionVersionMapper workflowVersionMapper;

  private WorkflowDefinitionWriteSupport workflowWriteSupport;

  @Mock
  private DefaultSingleJobWorkflowProvisioner singleJobWorkflowProvisioner;

  private JobMonitoringDefaultsProperties monitoringDefaults;

  @Mock
  private ConsoleConfigCacheInvalidationService cacheInvalidationService;

  private TenantConfigPackageExcelApplyService service;

  @BeforeEach
  void setUp() {
    workflowWriteSupport = new WorkflowDefinitionWriteSupport(
        workflowNodeMapper,
        workflowEdgeMapper,
        workflowVersionMapper,
        JsonUtils.newDefaultMapper());
    monitoringDefaults = new JobMonitoringDefaultsProperties();
    monitoringDefaults.setSoftRuntimeSeconds(1800);
    monitoringDefaults.setStartGraceSeconds(300);
    service = new TenantConfigPackageExcelApplyService(
        resourceQueueMapper,
        businessCalendarMapper,
        calendarHolidayMapper,
        batchWindowMapper,
        fileChannelConfigMapper,
        fileTemplateConfigMapper,
        jobDefinitionMapper,
        singleJobWorkflowProvisioner,
        monitoringDefaults,
        workflowDefinitionMapper,
        workflowMonitoringPolicyMapper,
        workflowNodeMapper,
        workflowEdgeMapper,
        workflowWriteSupport,
        cacheInvalidationService);
  }

  @Test
  @DisplayName("导入 Cron 策略时解析计划完成时刻并补齐默认告警级别")
  void shouldApplyCronMonitoringPolicy_withDefaults() {
    JobDefinitionView job = new JobDefinitionView();
    job.setId(41L);
    job.setJobCode("JOB_DAILY");
    job.setScheduleType("CRON");
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_DAILY")).thenReturn(job);

    service.applyAll(validationResult(policyRow("23:45")), context());

    ArgumentCaptor<JobMonitoringPolicyUpsertParam> captor =
        ArgumentCaptor.forClass(JobMonitoringPolicyUpsertParam.class);
    verify(jobDefinitionMapper).upsertJobMonitoringPolicy(captor.capture());
    JobMonitoringPolicyUpsertParam policy = captor.getValue();
    assertThat(policy.getJobDefinitionId()).isEqualTo(41L);
    assertThat(policy.getSoftRuntimeSeconds()).isEqualTo(900);
    assertThat(policy.getSoftRuntimeSeverity()).isEqualTo("WARN");
    assertThat(policy.getStartGraceSeconds()).isEqualTo(300);
    assertThat(policy.getStartGraceSeverity()).isEqualTo("WARN");
    assertThat(policy.getCompletionDeadlineLocalTime()).isEqualTo(LocalTime.of(23, 45));
    assertThat(policy.getCompletionDeadlineDayOffset()).isEqualTo(1);
    assertThat(policy.getCompletionDeadlineSeverity()).isEqualTo("WARN");
  }

  @Test
  @DisplayName("Workflow 策略按租户与工作流版本写入独立监控表")
  void shouldApplyWorkflowMonitoringPolicyByDefinitionVersion() {
    WorkflowDefinitionEntity definition = new WorkflowDefinitionEntity();
    definition.setId(93L);
    definition.setTenantId("tenant-a");
    definition.setWorkflowCode("WF_DAILY");
    definition.setVersion(2);
    when(workflowDefinitionMapper.selectByUniqueKey("tenant-a", "WF_DAILY", 2))
        .thenReturn(definition);
    when(workflowMonitoringPolicyMapper.selectByDefinition("tenant-a", 93L)).thenReturn(null);
    Map<String, String> row = Map.ofEntries(
        Map.entry("workflow_code", "WF_DAILY"),
        Map.entry("workflow_version", "2"),
        Map.entry(COL_SOFT_RUNTIME_SECONDS, "600"),
        Map.entry(COL_SOFT_RUNTIME_SEVERITY, "ERROR"),
        Map.entry(COL_START_GRACE_SECONDS, "120"),
        Map.entry(COL_START_GRACE_SEVERITY, "CRITICAL"),
        Map.entry(COL_COMPLETION_DEADLINE_LOCAL_TIME, "04:30"),
        Map.entry(COL_COMPLETION_DEADLINE_DAY_OFFSET, "1"),
        Map.entry(COL_COMPLETION_DEADLINE_SEVERITY, "WARN"));
    SheetResult empty = new SheetResult("empty", 0, List.of(), List.of());
    SheetResult policies =
        new SheetResult("workflow_monitoring_policy", 1, List.of(row), List.of());
    PackageValidationResult result = PackageValidationResult.builder()
        .resourceQueues(empty)
        .businessCalendars(empty)
        .batchWindows(empty)
        .jobs(empty)
        .jobMonitoringPolicies(empty)
        .channels(empty)
        .fileTemplates(empty)
        .pipelines(empty)
        .steps(empty)
        .wfDefs(empty)
        .wfNodes(empty)
        .wfEdges(empty)
        .workflowMonitoringPolicies(policies)
        .crossRefIssues(List.of())
        .build();

    service.applyAll(result, context());

    ArgumentCaptor<WorkflowMonitoringPolicyEntity> captor =
        ArgumentCaptor.forClass(WorkflowMonitoringPolicyEntity.class);
    verify(workflowMonitoringPolicyMapper).setTenantContext("tenant-a");
    verify(workflowMonitoringPolicyMapper).upsert(captor.capture());
    WorkflowMonitoringPolicyEntity policy = captor.getValue();
    assertThat(policy.getTenantId()).isEqualTo("tenant-a");
    assertThat(policy.getWorkflowDefinitionId()).isEqualTo(93L);
    assertThat(policy.getSoftRuntimeSeconds()).isEqualTo(600);
    assertThat(policy.getSoftRuntimeSeverity()).isEqualTo("ERROR");
    assertThat(policy.getStartGraceSeconds()).isEqualTo(120);
    assertThat(policy.getStartGraceSeverity()).isEqualTo("CRITICAL");
    assertThat(policy.getCompletionDeadlineLocalTime()).isEqualTo(LocalTime.of(4, 30));
    assertThat(policy.getCompletionDeadlineDayOffset()).isOne();
    assertThat(policy.getCompletionDeadlineSeverity()).isEqualTo("WARN");
  }

  @Test
  @DisplayName("工作流 Excel 导入按目标租户作业编码恢复关联并迁移调度配置")
  void shouldImportWorkflowAssociationAndSchedule() {
    when(workflowNodeMapper.selectByQuery(any(WorkflowNodeQuery.class))).thenReturn(List.of());
    when(workflowEdgeMapper.selectByQuery(any(WorkflowEdgeQuery.class))).thenReturn(List.of());
    JobDefinitionView job = new JobDefinitionView();
    job.setId(81L);
    job.setJobCode("JOB_DAILY");
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_DAILY")).thenReturn(job);
    WorkflowDefinitionEntity created = new WorkflowDefinitionEntity();
    created.setId(92L);
    created.setWorkflowCode("WF_JOB_DAILY");
    created.setVersion(1);
    when(workflowDefinitionMapper.selectByUniqueKey("tenant-a", "WF_JOB_DAILY", 1))
        .thenReturn(null, created);
    Map<String, String> workflowRow = Map.ofEntries(
        Map.entry("workflow_code", "WF_JOB_DAILY"),
        Map.entry("workflow_name", "Daily workflow"),
        Map.entry("workflow_type", "DAG"),
        Map.entry("version", "1"),
        Map.entry("single_job_code", "JOB_DAILY"),
        Map.entry("schedule_enabled", "true"),
        Map.entry("schedule_type", "CRON"),
        Map.entry("schedule_expr", "0 0 2 * * ?"),
        Map.entry("timezone", "Asia/Shanghai"),
        Map.entry("trigger_mode", "SCHEDULED"),
        Map.entry("calendar_code", "CN_WORKDAY"),
        Map.entry("depends_on_job_code", "JOB_PREVIOUS"));
    SheetResult empty = new SheetResult("empty", 0, List.of(), List.of());
    SheetResult workflows =
        new SheetResult("workflow_definition", 1, List.of(workflowRow), List.of());
    service.applyAll(
        new PackageValidationResult(
            empty, empty, empty, empty, empty, empty, empty, empty, empty, workflows, empty, empty,
            List.of()),
        context());

    ArgumentCaptor<WorkflowDefinitionUpsertParam> captor =
        ArgumentCaptor.forClass(WorkflowDefinitionUpsertParam.class);
    verify(workflowDefinitionMapper).upsertWorkflowDefinition(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getSingleJobDefinitionId()).isEqualTo(81L);
    assertThat(saved.getScheduleEnabled()).isTrue();
    assertThat(saved.getScheduleType()).isEqualTo("CRON");
    assertThat(saved.getScheduleExpr()).isEqualTo("0 0 2 * * ?");
    assertThat(saved.getTimezone()).isEqualTo("Asia/Shanghai");
    assertThat(saved.getTriggerMode()).isEqualTo("SCHEDULED");
    assertThat(saved.getCalendarCode()).isEqualTo("cn_workday");
    assertThat(saved.getDependsOnJobCode()).isEqualTo("JOB_PREVIOUS");
    ArgumentCaptor<WorkflowDefinitionVersionInsertParam> versionCaptor =
        ArgumentCaptor.forClass(WorkflowDefinitionVersionInsertParam.class);
    verify(workflowVersionMapper).insertVersionSnapshot(versionCaptor.capture());
    assertThat(versionCaptor.getValue().getScheduleEnabled()).isTrue();
  }

  @Test
  @DisplayName("跨租户导入受管单作业 Workflow 时复用目标关联并递增版本")
  void shouldReuseTargetManagedWorkflowWhenImportingPortablePackage() {
    when(workflowNodeMapper.selectByQuery(any(WorkflowNodeQuery.class))).thenReturn(List.of());
    when(workflowEdgeMapper.selectByQuery(any(WorkflowEdgeQuery.class))).thenReturn(List.of());
    JobDefinitionView job = new JobDefinitionView();
    job.setId(81L);
    job.setJobCode("JOB_DAILY");
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_DAILY")).thenReturn(job);
    WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity();
    existing.setId(91L);
    existing.setTenantId("tenant-a");
    existing.setWorkflowCode("TENANT_DEFAULT_FLOW");
    existing.setWorkflowName("Existing default flow");
    existing.setWorkflowType("DAG");
    existing.setVersion(4);
    existing.setEnabled(true);
    existing.setSingleJobDefinitionId(81L);
    when(workflowDefinitionMapper.selectBySingleJobDefinitionId("tenant-a", 81L))
        .thenReturn(existing);
    when(workflowDefinitionMapper.updateAndBumpVersion(new WorkflowDefinitionVersionUpdateParam(
            "tenant-a",
            91L,
            4,
            "Daily workflow",
            "DAG",
            true,
            true,
            "CRON",
            "0 0 2 * * ?",
            "Asia/Shanghai",
            "SCHEDULED",
            "cn_workday",
            "JOB_PREVIOUS",
            null)))
        .thenReturn(1);
    Map<String, String> workflowRow = Map.ofEntries(
        Map.entry("workflow_code", "SOURCE_DEFAULT_FLOW"),
        Map.entry("workflow_name", "Daily workflow"),
        Map.entry("workflow_type", "DAG"),
        Map.entry("version", "2"),
        Map.entry("single_job_code", "JOB_DAILY"),
        Map.entry("schedule_enabled", "true"),
        Map.entry("schedule_type", "CRON"),
        Map.entry("schedule_expr", "0 0 2 * * ?"),
        Map.entry("timezone", "Asia/Shanghai"),
        Map.entry("trigger_mode", "SCHEDULED"),
        Map.entry("calendar_code", "CN_WORKDAY"),
        Map.entry("depends_on_job_code", "JOB_PREVIOUS"));
    SheetResult empty = new SheetResult("empty", 0, List.of(), List.of());
    SheetResult workflows =
        new SheetResult("workflow_definition", 1, List.of(workflowRow), List.of());

    service.applyAll(
        new PackageValidationResult(
            empty, empty, empty, empty, empty, empty, empty, empty, empty, workflows, empty, empty,
            List.of()),
        context());

    verify(workflowDefinitionMapper, never()).upsertWorkflowDefinition(any());
    ArgumentCaptor<WorkflowDefinitionVersionInsertParam> versionCaptor =
        ArgumentCaptor.forClass(WorkflowDefinitionVersionInsertParam.class);
    verify(workflowVersionMapper).insertVersionSnapshot(versionCaptor.capture());
    assertThat(versionCaptor.getValue().getWorkflowDefinitionId()).isEqualTo(91L);
    assertThat(versionCaptor.getValue().getWorkflowCode()).isEqualTo("TENANT_DEFAULT_FLOW");
    assertThat(versionCaptor.getValue().getVersion()).isEqualTo(5);
    assertThat(versionCaptor.getValue().getScheduleEnabled()).isTrue();
  }

  @Test
  @DisplayName("配置包更新Workflow日历前拒绝已有周期预期定义")
  void shouldRejectWorkflowCalendarChange_whenPackageHasRecurringExpectations() {
    var existing = new WorkflowDefinitionEntity();
    existing.setId(91L);
    existing.setTenantId("tenant-a");
    existing.setWorkflowCode("WF_DAILY");
    existing.setWorkflowName("Daily workflow");
    existing.setWorkflowType("DAG");
    existing.setVersion(1);
    existing.setEnabled(true);
    existing.setCalendarCode("OLD_CALENDAR");
    when(workflowDefinitionMapper.selectByUniqueKey("tenant-a", "WF_DAILY", 1))
        .thenReturn(existing);
    when(workflowDefinitionMapper.hasCalendarScopedExpectations("tenant-a", 91L))
        .thenReturn(true);
    Map<String, String> workflowRow = Map.ofEntries(
        Map.entry("workflow_code", "WF_DAILY"),
        Map.entry("workflow_name", "Daily workflow"),
        Map.entry("workflow_type", "DAG"),
        Map.entry("version", "1"),
        Map.entry("schedule_enabled", "true"),
        Map.entry("schedule_type", "CRON"),
        Map.entry("schedule_expr", "0 0 2 * * ?"),
        Map.entry("timezone", "Asia/Shanghai"),
        Map.entry("trigger_mode", "SCHEDULED"),
        Map.entry("calendar_code", "CN_WORKDAY"));
    SheetResult empty = new SheetResult("empty", 0, List.of(), List.of());
    SheetResult workflows =
        new SheetResult("workflow_definition", 1, List.of(workflowRow), List.of());

    assertThatThrownBy(() -> service.applyAll(
            new PackageValidationResult(
                empty, empty, empty, empty, empty, empty, empty, empty, empty, workflows, empty,
                empty, List.of()),
            context()))
        .isInstanceOf(BizException.class)
        .extracting(exception -> ((BizException) exception).getMessageKey())
        .isEqualTo("error.workflow.calendar_has_expectations");

    verify(workflowDefinitionMapper, never()).updateAndBumpVersion(any());
    verify(workflowDefinitionMapper, never()).upsertWorkflowDefinition(any());
  }

  @Test
  @DisplayName("场景模板未包含的平台监控列保留现有策略")
  void shouldPreserveMonitoringFieldsOmittedByScenarioTemplate() {
    JobDefinitionView job = new JobDefinitionView();
    job.setId(42L);
    job.setJobCode("JOB_DAILY");
    job.setScheduleType("CRON");
    job.setSoftRuntimeSeconds(2400);
    job.setSoftRuntimeSeverity("ERROR");
    job.setStartGraceSeconds(600);
    job.setStartGraceSeverity("ERROR");
    job.setCompletionDeadlineDayOffset(2);
    job.setDependencyCompletionWindowSeconds(1800);
    job.setCompletionDeadlineSeverity("ERROR");
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_DAILY")).thenReturn(job);

    Map<String, String> schedulePolicy = Map.of(
        COL_JOB_CODE, "JOB_DAILY",
        COL_COMPLETION_DEADLINE_LOCAL_TIME, "04:00",
        COL_COMPLETION_DEADLINE_DAY_OFFSET, "1",
        COL_DEPENDENCY_COMPLETION_WINDOW_SECONDS, "0",
        COL_COMPLETION_DEADLINE_SEVERITY, "WARN");
    service.applyAll(validationResult(schedulePolicy), context());

    ArgumentCaptor<JobMonitoringPolicyUpsertParam> captor =
        ArgumentCaptor.forClass(JobMonitoringPolicyUpsertParam.class);
    verify(jobDefinitionMapper).upsertJobMonitoringPolicy(captor.capture());
    JobMonitoringPolicyUpsertParam policy = captor.getValue();
    assertThat(policy.getSoftRuntimeSeconds()).isEqualTo(2400);
    assertThat(policy.getSoftRuntimeSeverity()).isEqualTo("ERROR");
    assertThat(policy.getStartGraceSeconds()).isEqualTo(600);
    assertThat(policy.getStartGraceSeverity()).isEqualTo("ERROR");
    assertThat(policy.getCompletionDeadlineDayOffset()).isEqualTo(1);
    assertThat(policy.getDependencyCompletionWindowSeconds()).isZero();
  }

  @Test
  @DisplayName("监控策略引用不存在的租户作业时拒绝整包应用")
  void shouldRejectPolicy_whenJobDoesNotExist() {

    assertThatThrownBy(() -> service.applyAll(validationResult(policyRow("")), context()))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("精简作业行更新时保留未填写和空白字段")
  void shouldPreserveExistingJobValues_whenScenarioRowOmitsFields() {
    JobDefinitionView existing = new JobDefinitionView();
    existing.setJobCode("JOB_DAILY");
    existing.setJobName("Existing name");
    existing.setQueueCode("TENANT_QUEUE");
    existing.setWorkerGroup("IMPORT_GROUP");
    existing.setScheduleType("CRON");
    existing.setScheduleExpr("0 0 2 * * ?");
    existing.setRetryPolicy("FIXED");
    existing.setRetryMaxCount(5);
    existing.setTimeoutSeconds(1200);
    existing.setShardStrategy("NONE");
    existing.setExecutionMode("INCREMENTAL");
    existing.setWatermarkField("updated_at");
    existing.setEnabled(true);
    existing.setDescription("Existing description");
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_DAILY")).thenReturn(existing);

    Map<String, String> partialJobRow = Map.of(
        COL_JOB_CODE, "JOB_DAILY",
        COL_JOB_NAME, "   ");
    SheetResult empty = new SheetResult("empty", 0, List.of(), List.of());
    SheetResult jobs = new SheetResult("job_definition", 1, List.of(partialJobRow), List.of());
    PackageValidationResult result = new PackageValidationResult(
        empty, empty, empty, jobs, empty, empty, empty, empty, empty, empty, empty, empty,
        List.of());

    service.applyAll(result, context());

    ArgumentCaptor<JobDefinitionMaintenanceUpdateParam> captor =
        ArgumentCaptor.forClass(JobDefinitionMaintenanceUpdateParam.class);
    verify(jobDefinitionMapper).updateJobDefinitionMaintenance(captor.capture());
    JobDefinitionMaintenanceUpdateParam update = captor.getValue();
    assertThat(update.getJobName()).isEqualTo("Existing name");
    assertThat(update.getQueueCode()).isEqualTo("TENANT_QUEUE");
    assertThat(update.getWorkerGroup()).isEqualTo("IMPORT_GROUP");
    assertThat(update.getScheduleExpr()).isEqualTo("0 0 2 * * ?");
    assertThat(update.getRetryPolicy()).isEqualTo("FIXED");
    assertThat(update.getRetryMaxCount()).isEqualTo(5);
    assertThat(update.getTimeoutSeconds()).isEqualTo(1200);
    assertThat(update.getExecutionMode()).isEqualTo("INCREMENTAL");
    assertThat(update.getWatermarkField()).isEqualTo("updated_at");
    assertThat(update.getEnabled()).isTrue();
    assertThat(update.getDescription()).isEqualTo("Existing description");
  }

  @Test
  @DisplayName("场景化通道更新留空时保留当前端点与认证配置")
  void shouldPreserveExistingChannelValues_whenScenarioRowLeavesOptionalFieldsBlank() {
    FileChannelConfigQueryView existing = FileChannelConfigQueryView.builder()
        .channelName("Existing channel")
        .channelType("SFTP")
        .targetEndpoint("sftp://files.example/in")
        .authType("SSH_KEY")
        .configJson("{\"secret_ref\":\"vault://tenant/key\"}")
        .receiptPolicy("CHECKSUM")
        .timeoutSeconds(90)
        .enabled(false)
        .build();
    when(fileChannelConfigMapper.selectByUniqueKey("tenant-a", "PARTNER_SFTP"))
        .thenReturn(existing);
    Map<String, String> channelRow = new LinkedHashMap<>();
    channelRow.put("channel_code", "PARTNER_SFTP");
    channelRow.put("channel_name", "Updated name");
    channelRow.put("channel_type", "");
    channelRow.put("target_endpoint", "");
    channelRow.put("auth_type", "");
    channelRow.put("config_json", "");
    channelRow.put("receipt_policy", "");
    channelRow.put("timeout_seconds", "");
    channelRow.put("enabled", "");
    SheetResult empty = new SheetResult("empty", 0, List.of(), List.of());
    SheetResult channels =
        new SheetResult("file_channel_config", 1, List.of(channelRow), List.of());

    service.applyAll(
        new PackageValidationResult(
            empty, empty, empty, empty, empty, channels, empty, empty, empty, empty, empty, empty,
            List.of()),
        context());

    ArgumentCaptor<FileChannelConfigUpsertParam> captor =
        ArgumentCaptor.forClass(FileChannelConfigUpsertParam.class);
    verify(fileChannelConfigMapper).upsertFileChannelConfig(captor.capture());
    FileChannelConfigUpsertParam saved = captor.getValue();
    assertThat(saved.getChannelName()).isEqualTo("Updated name");
    assertThat(saved.getChannelType()).isEqualTo("SFTP");
    assertThat(saved.getTargetEndpoint()).isEqualTo("sftp://files.example/in");
    assertThat(saved.getAuthType()).isEqualTo("SSH_KEY");
    assertThat(saved.getConfigJson()).isEqualTo("{\"secret_ref\":\"vault://tenant/key\"}");
    assertThat(saved.getTimeoutSeconds()).isEqualTo(90);
    assertThat(saved.getEnabled()).isFalse();
  }

  @Test
  @DisplayName("新建渠道的配置 JSON 留空时使用空对象，不写入非法 JSON")
  void shouldUseEmptyObject_whenNewChannelConfigurationIsBlank() {
    when(fileChannelConfigMapper.selectByUniqueKey("tenant-a", "NEW_CHANNEL")).thenReturn(null);
    Map<String, String> channelRow = new LinkedHashMap<>();
    channelRow.put("channel_code", "NEW_CHANNEL");
    channelRow.put("channel_name", "New channel");
    channelRow.put("channel_type", "LOCAL");
    channelRow.put("target_endpoint", "/data/out");
    channelRow.put("auth_type", "NONE");
    channelRow.put("config_json", "");
    channelRow.put("receipt_policy", "NONE");
    channelRow.put("timeout_seconds", "30");
    channelRow.put("enabled", "true");
    SheetResult empty = new SheetResult("empty", 0, List.of(), List.of());
    SheetResult channels =
        new SheetResult("file_channel_config", 1, List.of(channelRow), List.of());

    service.applyAll(
        new PackageValidationResult(
            empty, empty, empty, empty, empty, channels, empty, empty, empty, empty, empty, empty,
            List.of()),
        context());

    ArgumentCaptor<FileChannelConfigUpsertParam> captor =
        ArgumentCaptor.forClass(FileChannelConfigUpsertParam.class);
    verify(fileChannelConfigMapper).upsertFileChannelConfig(captor.capture());
    assertThat(captor.getValue().getConfigJson()).isEqualTo("{}");
  }

  @Test
  @DisplayName("新建需要认证的渠道时拒绝空配置")
  void shouldRejectBlankConfiguration_whenCreatingAuthenticatedChannel() {
    when(fileChannelConfigMapper.selectByUniqueKey("tenant-a", "NEW_SFTP")).thenReturn(null);
    Map<String, String> channelRow = new LinkedHashMap<>();
    channelRow.put("channel_code", "NEW_SFTP");
    channelRow.put("channel_name", "New SFTP");
    channelRow.put("channel_type", "SFTP");
    channelRow.put("target_endpoint", "sftp.example.com");
    channelRow.put("auth_type", "PASSWORD");
    channelRow.put("config_json", "");
    channelRow.put("receipt_policy", "NONE");
    channelRow.put("timeout_seconds", "30");
    channelRow.put("enabled", "true");
    SheetResult empty = new SheetResult("empty", 0, List.of(), List.of());
    SheetResult channels =
        new SheetResult("file_channel_config", 1, List.of(channelRow), List.of());

    assertThatThrownBy(() -> service.applyAll(
            new PackageValidationResult(
                empty, empty, empty, empty, empty, channels, empty, empty, empty, empty, empty,
                empty, List.of()),
            context()))
        .isInstanceOf(BizException.class);
  }

  private static TenantConfigPackageExcelApplyService.ApplyContext context() {
    return new TenantConfigPackageExcelApplyService.ApplyContext(
        "tenant-a", "tester", "test", "trace-1");
  }

  private static Map<String, String> policyRow(String deadline) {
    return Map.of(
        COL_JOB_CODE, "JOB_DAILY",
        COL_SOFT_RUNTIME_SECONDS, "900",
        COL_SOFT_RUNTIME_SEVERITY, "",
        COL_START_GRACE_SECONDS, "",
        COL_START_GRACE_SEVERITY, "",
        COL_COMPLETION_DEADLINE_LOCAL_TIME, deadline,
        COL_COMPLETION_DEADLINE_DAY_OFFSET, "1",
        COL_DEPENDENCY_COMPLETION_WINDOW_SECONDS, "0",
        COL_COMPLETION_DEADLINE_SEVERITY, "");
  }

  private static PackageValidationResult validationResult(Map<String, String> policyRow) {
    SheetResult empty = new SheetResult("empty", 0, List.of(), List.of());
    SheetResult policy = new SheetResult("job_monitoring_policy", 1, List.of(policyRow), List.of());
    return new PackageValidationResult(
        empty, empty, empty, empty, policy, empty, empty, empty, empty, empty, empty, empty,
        List.of());
  }
}
