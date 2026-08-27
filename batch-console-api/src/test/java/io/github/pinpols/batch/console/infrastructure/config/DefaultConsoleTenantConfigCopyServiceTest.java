package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.query.FileTemplateConfigQuery;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.query.JobDefinitionQuery;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertRoutingConfigMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineStepDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowDefinitionQuery;
import io.github.pinpols.batch.console.web.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.web.request.config.TenantConfigCopyRequest;
import io.github.pinpols.batch.console.web.response.config.TenantConfigBatchInitResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultConsoleTenantConfigCopyServiceTest {

  private JobDefinitionMapper jobDefinitionMapper;
  private WorkflowDefinitionMapper workflowDefinitionMapper;
  private WorkflowNodeMapper workflowNodeMapper;
  private WorkflowEdgeMapper workflowEdgeMapper;
  private PipelineDefinitionMapper pipelineDefinitionMapper;
  private PipelineStepDefinitionMapper pipelineStepDefinitionMapper;
  private FileChannelConfigMapper fileChannelConfigMapper;
  private FileTemplateConfigMapper fileTemplateConfigMapper;
  private ResourceQueueMapper resourceQueueMapper;
  private BatchWindowMapper batchWindowMapper;
  private BusinessCalendarMapper businessCalendarMapper;
  private CalendarHolidayMapper calendarHolidayMapper;
  private TenantQuotaPolicyMapper tenantQuotaPolicyMapper;
  private AlertRoutingConfigMapper alertRoutingConfigMapper;
  private ConsoleTenantConfigInitApplicationService initService;
  private DefaultConsoleTenantConfigCopyService service;

  @BeforeEach
  void setUp() {
    jobDefinitionMapper = mock(JobDefinitionMapper.class);
    workflowDefinitionMapper = mock(WorkflowDefinitionMapper.class);
    workflowNodeMapper = mock(WorkflowNodeMapper.class);
    workflowEdgeMapper = mock(WorkflowEdgeMapper.class);
    pipelineDefinitionMapper = mock(PipelineDefinitionMapper.class);
    pipelineStepDefinitionMapper = mock(PipelineStepDefinitionMapper.class);
    fileChannelConfigMapper = mock(FileChannelConfigMapper.class);
    fileTemplateConfigMapper = mock(FileTemplateConfigMapper.class);
    resourceQueueMapper = mock(ResourceQueueMapper.class);
    batchWindowMapper = mock(BatchWindowMapper.class);
    businessCalendarMapper = mock(BusinessCalendarMapper.class);
    calendarHolidayMapper = mock(CalendarHolidayMapper.class);
    tenantQuotaPolicyMapper = mock(TenantQuotaPolicyMapper.class);
    alertRoutingConfigMapper = mock(AlertRoutingConfigMapper.class);
    initService = mock(ConsoleTenantConfigInitApplicationService.class);
    service = new DefaultConsoleTenantConfigCopyService(
        jobDefinitionMapper,
        workflowDefinitionMapper,
        workflowNodeMapper,
        workflowEdgeMapper,
        pipelineDefinitionMapper,
        pipelineStepDefinitionMapper,
        fileChannelConfigMapper,
        fileTemplateConfigMapper,
        resourceQueueMapper,
        batchWindowMapper,
        businessCalendarMapper,
        calendarHolidayMapper,
        tenantQuotaPolicyMapper,
        alertRoutingConfigMapper,
        initService,
        new TenantConfigReferenceResolver());
  }

  @Test
  void buildJobBundleUsesExplicitTemplateAndChannelReferences() {
    stubExplicitReferenceBundle();

    ConfigSyncBundlePayload bundle = service.buildJobBundle("ta", "JOB_A");

    assertThat(bundle.getFileTemplates())
        .extracting("templateCode")
        .containsExactly("tpl-from-job", "tpl-from-step");
    assertThat(bundle.getFileChannels()).extracting("channelCode").containsExactly("chan-explicit");
  }

  @Test
  void copyWithJobCodesDelegatesMinimalDependencyBundleToBatchInit() {
    stubExplicitReferenceBundle();
    when(initService.batchInit(any(TenantConfigBatchInitRequest.class), anyString(), anyString()))
        .thenReturn(new TenantConfigBatchInitResponse("op-1", 1, 1, 0, false, List.of()));
    TenantConfigCopyRequest request = new TenantConfigCopyRequest();
    request.setSourceTenantId("ta");
    request.setTargetTenantIds(List.of("tb"));
    request.setJobCodes(List.of("JOB_A"));

    service.copy(request, "admin", "op-1");

    ArgumentCaptor<TenantConfigBatchInitRequest> captor =
        ArgumentCaptor.forClass(TenantConfigBatchInitRequest.class);
    verify(initService).batchInit(captor.capture(), anyString(), anyString());
    TenantConfigBatchInitRequest delegated = captor.getValue();
    assertThat(delegated.getTargetTenantIds()).containsExactly("tb");
    assertThat(delegated.getJobDefinitions()).extracting("jobCode").containsExactly("JOB_A");
    assertThat(delegated.getFileTemplates())
        .extracting("templateCode")
        .containsExactly("tpl-from-job", "tpl-from-step");
    assertThat(delegated.getFileChannels())
        .extracting("channelCode")
        .containsExactly("chan-explicit");
  }

  private void stubExplicitReferenceBundle() {
    JobDefinitionEntity job = new JobDefinitionEntity();
    job.setJobCode("JOB_A");
    job.setBizType("PAYROLL");
    job.setQueueCode("q1");
    job.setWindowCode("nightly");
    job.setCalendarCode("cn");
    job.setDefaultParams("{\"fileTemplateCode\":\"tpl-from-job\"}");
    when(jobDefinitionMapper.selectByQuery(any(JobDefinitionQuery.class))).thenReturn(List.of(job));
    when(pipelineDefinitionMapper.selectByQuery(
            anyString(), isNull(), isNull(), isNull(), any(PageRequest.class)))
        .thenReturn(List.of(Map.of(
            "id", 10L,
            "job_code", "JOB_A",
            "pipeline_name", "pipe",
            "pipeline_type", "EXPORT",
            "biz_type", "PAYROLL",
            "enabled", true)));
    when(pipelineStepDefinitionMapper.selectByPipelineDefinitionId(10L))
        .thenReturn(List.of(Map.of(
            "step_code",
            "generate",
            "step_params",
            "{\"templateCode\":\"tpl-from-step\",\"channelCode\":\"chan-explicit\"}",
            "enabled",
            true)));
    when(workflowDefinitionMapper.selectByQuery(any(WorkflowDefinitionQuery.class)))
        .thenReturn(List.of());
    when(resourceQueueMapper.selectByQuery(
            anyString(), isNull(), isNull(), isNull(), any(PageRequest.class)))
        .thenReturn(List.of(Map.of("queue_code", "q1", "enabled", true)));
    when(batchWindowMapper.selectByQuery(anyString(), isNull(), isNull(), any(PageRequest.class)))
        .thenReturn(List.of(Map.of("window_code", "nightly", "enabled", true)));
    when(businessCalendarMapper.selectByQuery(
            anyString(), isNull(), isNull(), any(PageRequest.class)))
        .thenReturn(List.of(Map.of("calendar_code", "cn", "enabled", true)));
    when(fileTemplateConfigMapper.selectByQuery(any(FileTemplateConfigQuery.class)))
        .thenReturn(List.of(
            Map.of("template_code", "tpl-from-job", "biz_type", "OTHER", "enabled", true),
            Map.of("template_code", "tpl-from-step", "biz_type", "OTHER", "enabled", true),
            Map.of("template_code", "tpl-legacy", "biz_type", "PAYROLL", "enabled", true)));
    when(fileChannelConfigMapper.selectByQuery(
            anyString(), isNull(), isNull(), isNull(), any(PageRequest.class)))
        .thenReturn(List.of(
            Map.of("channel_code", "chan-explicit", "enabled", true),
            Map.of("channel_code", "PAYROLL", "enabled", true)));
    when(tenantQuotaPolicyMapper.selectByQuery(
            anyString(), isNull(), isNull(), any(PageRequest.class)))
        .thenReturn(List.of());
    when(alertRoutingConfigMapper.selectByQuery(
            anyString(), isNull(), isNull(), isNull(), isNull(), any(PageRequest.class)))
        .thenReturn(List.of());
  }
}
