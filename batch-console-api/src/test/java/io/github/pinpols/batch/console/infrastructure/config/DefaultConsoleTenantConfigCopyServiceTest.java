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
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.query.FileTemplateConfigQuery;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigQueryView;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDependencyRuleRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.JobDependencyRuleResponse;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.query.JobDefinitionQuery;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertRoutingConfigMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowDefinitionQuery;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("控制台租户配置复制服务: 显式引用组装与最小依赖委派")
class DefaultConsoleTenantConfigCopyServiceTest {

  private JobDefinitionMapper jobDefinitionMapper;
  private WorkflowDefinitionMapper workflowDefinitionMapper;
  private WorkflowNodeMapper workflowNodeMapper;
  private WorkflowEdgeMapper workflowEdgeMapper;
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
  @DisplayName("作业同步包从 Job 参数解析模板与通道引用")
  void shouldBuildBundleWithExplicitReferences_whenJobBundleIsAssembled() {
    stubExplicitReferenceBundle();

    ConfigSyncBundlePayload bundle = service.buildJobBundle("ta", "JOB_A");

    assertThat(bundle.getFileTemplates())
        .extracting("templateCode")
        .containsExactly("tpl-from-job", "tpl-from-step");
    assertThat(bundle.getFileTemplates())
        .filteredOn(template -> "tpl-from-job".equals(template.getTemplateCode()))
        .singleElement()
        .satisfies(template -> {
          assertThat(template.getHeaderTemplateJson()).isEqualTo("{\"header\":true}");
          assertThat(template.getTrailerTemplateJson()).isEqualTo("{\"trailer\":true}");
          assertThat(template.getFieldMappingsJson()).isEqualTo("[{\"from\":\"a\"}]");
          assertThat(template.getValidationRuleSetJson()).isEqualTo("{\"required\":[]}");
          assertThat(template.getQueryParamSchemaJson())
              .isEqualTo("{\"jdbcMappedImport\":{\"table\":\"customer_account\"}}");
        });
    assertThat(bundle.getFileChannels()).extracting("channelCode").containsExactly("chan-explicit");
    assertThat(bundle.getJobDefinitions())
        .singleElement()
        .extracting("dependencyCompletionWindowSeconds")
        .isEqualTo(900);
    assertThat(bundle.getJobDefinitions()).singleElement().satisfies(job -> {
      assertThat(job.getScheduleType()).isEqualTo("CRON");
      assertThat(job.getScheduleExpr()).isEqualTo("0 0 1 * * *");
      assertThat(job.getTimezone()).isEqualTo("Asia/Shanghai");
      assertThat(job.getTriggerMode()).isEqualTo("SCHEDULED");
      assertThat(job.getDependsOnJobCode()).isEqualTo("UPSTREAM_A");
      assertThat(job.getDependencyRules())
          .containsExactly(
              new JobDependencyRuleRequest("UPSTREAM_A", "SAME_DAY"),
              new JobDependencyRuleRequest("UPSTREAM_B", "PREVIOUS_BIZ_DAY"));
    });
    assertThat(bundle.getWorkflowDefinitions()).singleElement().satisfies(workflow -> {
      assertThat(workflow.getSingleJobCode()).isEqualTo("JOB_A");
      assertThat(workflow.getScheduleType()).isEqualTo("CRON");
      assertThat(workflow.getScheduleExpr()).isEqualTo("0 0 2 * * *");
      assertThat(workflow.getTimezone()).isEqualTo("Asia/Shanghai");
    });
    assertThat(bundle.getBusinessCalendars())
        .extracting("calendarCode")
        .containsExactlyInAnyOrder("cn", "workflow-cn");
    assertThat(bundle.getBusinessCalendars())
        .filteredOn(calendar -> "workflow-cn".equals(calendar.getCalendarCode()))
        .singleElement()
        .extracting("holidays")
        .isEqualTo(List.of("2026-10-01"));
  }

  @Test
  @DisplayName("按作业编码发起复制时,只向下游委派最小依赖包")
  void shouldDelegateMinimalBundle_whenCopyIsRequestedByJobCodes() {
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
    assertThat(delegated.isStrict()).isTrue();
    assertThat(delegated.getJobDefinitions()).extracting("jobCode").containsExactly("JOB_A");
    assertThat(delegated.getFileTemplates())
        .extracting("templateCode")
        .containsExactly("tpl-from-job", "tpl-from-step");
    assertThat(delegated.getFileChannels())
        .extracting("channelCode")
        .containsExactly("chan-explicit");
    assertThat(delegated.getFileChannels()).singleElement().satisfies(channel -> {
      assertThat(channel.getTargetEndpoint()).isNull();
      assertThat(channel.getConfigJson()).isEqualTo("{}");
    });
  }

  @Test
  @DisplayName("构建作业复制包时忽略缺少编码的无关依赖配置")
  void shouldIgnoreUncodedConfigRows_whenBuildingJobBundle() {
    stubExplicitReferenceBundle();
    when(batchWindowMapper.selectByQuery(anyString(), isNull(), isNull(), any(PageRequest.class)))
        .thenReturn(List.of(
            Collections.singletonMap("window_code", null),
            Map.of("window_code", "nightly", "enabled", true)));
    when(businessCalendarMapper.selectByQuery(
            anyString(), isNull(), isNull(), any(PageRequest.class)))
        .thenReturn(List.of(
            Collections.singletonMap("calendarCode", null),
            Map.of("id", 301L, "calendarCode", "cn", "enabled", true),
            Map.of("id", 302L, "calendarCode", "workflow-cn", "enabled", true)));
    when(fileTemplateConfigMapper.selectByQuery(any(FileTemplateConfigQuery.class)))
        .thenReturn(List.of(
            templateConfigView(null, null, null),
            templateConfigView("tpl-from-job", "OTHER", true),
            templateConfigView("tpl-from-step", "OTHER", true)));
    when(fileChannelConfigMapper.selectByQuery(
            org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null
                && parameters.tenantId() != null
                && parameters.channelCode() == null
                && parameters.channelType() == null
                && parameters.enabled() == null
                && parameters.pageRequest() != null)))
        .thenReturn(List.of(
            channelConfigView(null, null, null, null),
            channelConfigView("chan-explicit", null, null, true)));

    ConfigSyncBundlePayload bundle = service.buildJobBundle("ta", "JOB_A");

    assertThat(bundle.getJobDefinitions()).extracting("jobCode").containsExactly("JOB_A");
    assertThat(bundle.getBusinessCalendars())
        .extracting("calendarCode")
        .containsExactly("cn", "workflow-cn");
    assertThat(bundle.getFileTemplates())
        .extracting("templateCode")
        .containsExactly("tpl-from-job", "tpl-from-step");
    assertThat(bundle.getFileChannels()).extracting("channelCode").containsExactly("chan-explicit");
  }

  private void stubExplicitReferenceBundle() {
    JobDefinitionEntity job = new JobDefinitionEntity();
    job.setId(101L);
    job.setJobCode("JOB_A");
    job.setBizType("PAYROLL");
    job.setScheduleType("CRON");
    job.setScheduleExpr("0 0 1 * * *");
    job.setTimezone("Asia/Shanghai");
    job.setTriggerMode("SCHEDULED");
    job.setDependsOnJobCode("UPSTREAM_A");
    job.setJobDependencyRules(List.of(
        new JobDependencyRuleResponse("UPSTREAM_A", "SAME_DAY"),
        new JobDependencyRuleResponse("UPSTREAM_B", "PREVIOUS_BIZ_DAY")));
    job.setQueueCode("q1");
    job.setWindowCode("nightly");
    job.setCalendarCode("cn");
    job.setDependencyCompletionWindowSeconds(900);
    job.setDefaultParams("{\"fileTemplateCode\":\"tpl-from-job\","
        + "\"systemPipelineStepParams\":{\"GENERATE\":{"
        + "\"templateCode\":\"tpl-from-step\",\"channelCode\":\"chan-explicit\"}}}");
    when(jobDefinitionMapper.selectByQuery(any(JobDefinitionQuery.class))).thenReturn(List.of(job));
    WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
    workflow.setId(201L);
    workflow.setWorkflowCode("JOB-WF-101");
    workflow.setSingleJobDefinitionId(job.getId());
    workflow.setScheduleEnabled(true);
    workflow.setScheduleType("CRON");
    workflow.setScheduleExpr("0 0 2 * * *");
    workflow.setTimezone("Asia/Shanghai");
    workflow.setCalendarCode("workflow-cn");
    workflow.setTriggerMode("SCHEDULED");
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setNodeCode("job");
    node.setRelatedJobCode("JOB_A");
    when(workflowDefinitionMapper.selectByQuery(any(WorkflowDefinitionQuery.class)))
        .thenReturn(List.of(workflow));
    when(workflowNodeMapper.selectByQuery(any())).thenReturn(List.of(node));
    when(workflowEdgeMapper.selectByQuery(any())).thenReturn(List.of());
    when(jobDefinitionMapper.selectById("ta", job.getId())).thenReturn(job);
    when(resourceQueueMapper.selectByQuery(
            org.mockito.ArgumentMatchers.argThat(query -> query != null
                && query.tenantId() != null
                && query.queueCode() == null
                && query.queueType() == null
                && query.enabled() == null
                && query.pageRequest() != null)))
        .thenReturn(List.of(Map.of("queue_code", "q1", "enabled", true)));
    when(batchWindowMapper.selectByQuery(anyString(), isNull(), isNull(), any(PageRequest.class)))
        .thenReturn(List.of(Map.of("window_code", "nightly", "enabled", true)));
    when(businessCalendarMapper.selectByQuery(
            anyString(), isNull(), isNull(), any(PageRequest.class)))
        .thenReturn(List.of(
            Map.of("id", 301L, "calendarCode", "cn", "enabled", true),
            Map.of("id", 302L, "calendarCode", "workflow-cn", "enabled", true),
            Map.of("id", 303L, "calendarCode", "unrelated", "enabled", true)));
    when(calendarHolidayMapper.selectByCalendarId(301L))
        .thenReturn(List.of(Map.of("bizDate", "2026-01-01")));
    when(calendarHolidayMapper.selectByCalendarId(302L))
        .thenReturn(List.of(Map.of("bizDate", "2026-10-01")));
    when(calendarHolidayMapper.selectByCalendarId(303L))
        .thenReturn(List.of(Map.of("bizDate", "2026-12-25")));
    when(fileTemplateConfigMapper.selectByQuery(any(FileTemplateConfigQuery.class)))
        .thenReturn(List.of(
            FileTemplateConfigQueryView.builder()
                .templateCode("tpl-from-job")
                .bizType("OTHER")
                .enabled(true)
                .headerTemplate("{\"header\":true}")
                .trailerTemplate("{\"trailer\":true}")
                .fieldMappings("[{\"from\":\"a\"}]")
                .validationRuleSet("{\"required\":[]}")
                .queryParamSchema("{\"jdbcMappedImport\":{\"table\":\"customer_account\"}}")
                .build(),
            templateConfigView("tpl-from-step", "OTHER", true),
            templateConfigView("tpl-legacy", "PAYROLL", true)));
    when(fileChannelConfigMapper.selectByQuery(
            org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null
                && parameters.tenantId() != null
                && parameters.channelCode() == null
                && parameters.channelType() == null
                && parameters.enabled() == null
                && parameters.pageRequest() != null)))
        .thenReturn(List.of(
            channelConfigView(
                "chan-explicit",
                "sftp://source-user:source-password@example.com/inbox",
                "{\"password\":\"source-password\"}",
                true),
            channelConfigView("PAYROLL", null, null, true)));
    when(tenantQuotaPolicyMapper.selectByQuery(
            anyString(), isNull(), isNull(), any(PageRequest.class)))
        .thenReturn(List.of());
    when(alertRoutingConfigMapper.selectByQuery(
            org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null
                && parameters.tenantId() != null
                && parameters.keyword() == null
                && parameters.routeCode() == null
                && parameters.team() == null
                && parameters.severity() == null
                && parameters.enabled() == null
                && parameters.pageRequest() != null)))
        .thenReturn(List.of());
  }

  private static FileChannelConfigQueryView channelConfigView(
      String channelCode, String targetEndpoint, Object configJson, Boolean enabled) {
    return FileChannelConfigQueryView.builder()
        .channelCode(channelCode)
        .targetEndpoint(targetEndpoint)
        .configJson(configJson)
        .enabled(enabled)
        .build();
  }

  private static FileTemplateConfigQueryView templateConfigView(
      String templateCode, String bizType, Boolean enabled) {
    return FileTemplateConfigQueryView.builder()
        .templateCode(templateCode)
        .bizType(bizType)
        .enabled(enabled)
        .build();
  }
}
