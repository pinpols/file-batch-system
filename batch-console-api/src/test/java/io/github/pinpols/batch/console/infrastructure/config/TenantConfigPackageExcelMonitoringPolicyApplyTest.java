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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.JobMonitoringDefaultsProperties;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.param.FileChannelConfigUpsertParam;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.JobMonitoringPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineStepDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PackageValidationResult;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SheetResult;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("配置包作业监控策略导入:默认值、时限解析与作业引用校验")
class TenantConfigPackageExcelMonitoringPolicyApplyTest {

  private final ResourceQueueMapper resourceQueueMapper = mock(ResourceQueueMapper.class);
  private final BusinessCalendarMapper businessCalendarMapper = mock(BusinessCalendarMapper.class);
  private final CalendarHolidayMapper calendarHolidayMapper = mock(CalendarHolidayMapper.class);
  private final BatchWindowMapper batchWindowMapper = mock(BatchWindowMapper.class);
  private final FileChannelConfigMapper fileChannelConfigMapper =
      mock(FileChannelConfigMapper.class);
  private final FileTemplateConfigMapper fileTemplateConfigMapper =
      mock(FileTemplateConfigMapper.class);
  private final JobDefinitionMapper jobDefinitionMapper = mock(JobDefinitionMapper.class);
  private final PipelineDefinitionMapper pipelineDefinitionMapper =
      mock(PipelineDefinitionMapper.class);
  private final PipelineStepDefinitionMapper pipelineStepDefinitionMapper =
      mock(PipelineStepDefinitionMapper.class);
  private final WorkflowDefinitionMapper workflowDefinitionMapper =
      mock(WorkflowDefinitionMapper.class);
  private final WorkflowNodeMapper workflowNodeMapper = mock(WorkflowNodeMapper.class);
  private final WorkflowEdgeMapper workflowEdgeMapper = mock(WorkflowEdgeMapper.class);
  private final JobMonitoringDefaultsProperties monitoringDefaults =
      new JobMonitoringDefaultsProperties();

  private TenantConfigPackageExcelApplyService service;

  @BeforeEach
  void setUp() {
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
        monitoringDefaults,
        pipelineDefinitionMapper,
        pipelineStepDefinitionMapper,
        workflowDefinitionMapper,
        workflowNodeMapper,
        workflowEdgeMapper);
  }

  @Test
  @DisplayName("导入 Cron 策略时解析计划完成时刻并补齐默认告警级别")
  void shouldApplyCronMonitoringPolicy_withDefaults() {
    JobDefinitionEntity job = new JobDefinitionEntity();
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
  @DisplayName("场景模板未包含的平台监控列保留现有策略")
  void shouldPreserveMonitoringFieldsOmittedByScenarioTemplate() {
    JobDefinitionEntity job = new JobDefinitionEntity();
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
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_MISSING")).thenReturn(null);

    assertThatThrownBy(() -> service.applyAll(validationResult(policyRow("")), context()))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("精简作业行更新时保留未填写和空白字段")
  void shouldPreserveExistingJobValues_whenScenarioRowOmitsFields() {
    JobDefinitionEntity existing = new JobDefinitionEntity();
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
    Map<String, Object> existing = Map.of(
        "channel_name", "Existing channel",
        "channel_type", "SFTP",
        "target_endpoint", "sftp://files.example/in",
        "auth_type", "SSH_KEY",
        "config_json", "{\"secret_ref\":\"vault://tenant/key\"}",
        "receipt_policy", "CHECKSUM",
        "timeout_seconds", 90,
        "enabled", false);
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

  @Test
  @DisplayName("流水线步骤合并按步骤编码更新，不删除未出现在本次文件中的步骤")
  @SuppressWarnings("unchecked")
  void shouldMergePipelineStepsWithoutReplacingTheCollection() {
    when(pipelineDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_IMPORT", 1))
        .thenReturn(Map.of("id", 501L, "pipeline_name", "Current pipeline"));
    when(pipelineStepDefinitionMapper.selectByPipelineDefinitionId(501L))
        .thenReturn(List.of(Map.of(
            "step_code", "LOAD",
            "step_name", "Existing load",
            "stage_code", "LOAD",
            "step_order", 4,
            "impl_code", "load-db",
            "step_params", "{\"target\":\"account\"}",
            "timeout_seconds", 90,
            "retry_policy", "FIXED",
            "retry_max_count", 3,
            "enabled", true)));
    Map<String, String> pipeline = Map.of(
        "job_code", "JOB_IMPORT", "pipeline_name", "", "pipeline_type", "IMPORT", "version", "1");
    Map<String, String> step = Map.ofEntries(
        Map.entry("job_code", "JOB_IMPORT"),
        Map.entry("version", "1"),
        Map.entry("step_code", "LOAD"),
        Map.entry("step_name", ""),
        Map.entry("stage_code", "LOAD"),
        Map.entry("step_order", ""),
        Map.entry("impl_code", ""),
        Map.entry("step_params", ""),
        Map.entry("timeout_seconds", ""),
        Map.entry("retry_policy", ""),
        Map.entry("retry_max_count", ""),
        Map.entry("enabled", ""));
    SheetResult empty = new SheetResult("empty", 0, List.of(), List.of());
    SheetResult pipelines = new SheetResult("pipeline_definition", 1, List.of(pipeline), List.of());
    SheetResult steps = new SheetResult("pipeline_step_definition", 1, List.of(step), List.of());

    service.applyAll(
        new PackageValidationResult(
            empty, empty, empty, empty, empty, empty, empty, pipelines, steps, empty, empty, empty,
            List.of()),
        context());

    org.mockito.ArgumentCaptor<List<Map<String, Object>>> captor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(pipelineStepDefinitionMapper).upsertBatch(captor.capture());
    verify(pipelineStepDefinitionMapper, org.mockito.Mockito.never())
        .deleteByPipelineDefinitionId(501L);
    Map<String, Object> saved = captor.getValue().getFirst();
    assertThat(saved.get("step_name")).isEqualTo("Existing load");
    assertThat(saved.get("step_params")).isEqualTo("{\"target\":\"account\"}");
    assertThat(saved.get("timeout_seconds")).isEqualTo(90);
    assertThat(saved.get("retry_policy")).isEqualTo("FIXED");
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
