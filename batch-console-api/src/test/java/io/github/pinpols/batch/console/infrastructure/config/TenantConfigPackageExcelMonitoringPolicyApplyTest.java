package io.github.pinpols.batch.console.infrastructure.config;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_COMPLETION_DEADLINE_DAY_OFFSET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_COMPLETION_DEADLINE_LOCAL_TIME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_COMPLETION_DEADLINE_SEVERITY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_DEPENDENCY_COMPLETION_WINDOW_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_JOB_CODE;
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
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
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
  @DisplayName("监控策略引用不存在的租户作业时拒绝整包应用")
  void shouldRejectPolicy_whenJobDoesNotExist() {
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_MISSING")).thenReturn(null);

    assertThatThrownBy(() -> service.applyAll(validationResult(policyRow("")), context()))
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
