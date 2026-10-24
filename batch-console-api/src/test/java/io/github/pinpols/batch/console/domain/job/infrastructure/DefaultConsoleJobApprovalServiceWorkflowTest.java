package io.github.pinpols.batch.console.domain.job.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.application.ops.ConsoleJobOperationsPort;
import io.github.pinpols.batch.console.application.workflow.ManagedJobWorkflowReferencePort;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchDayCatchUpRequest;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchDayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.shared.client.TriggerInternalRestClient;
import io.github.pinpols.batch.console.shared.command.ApprovalSubmitContext;
import io.github.pinpols.batch.console.shared.command.ConsoleCatchUpApprovalRequest;
import io.github.pinpols.batch.console.shared.command.ConsoleLaunchCommand;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("Console 批量日补跑与审批解析 Workflow 根目标")
class DefaultConsoleJobApprovalServiceWorkflowTest {

  private ConsoleJobOperationsPort operations;
  private BusinessCalendarMapper businessCalendarMapper;
  private JobDefinitionMapper jobDefinitionMapper;
  private ManagedJobWorkflowReferencePort managedJobWorkflowReference;
  private DefaultConsoleJobApprovalService service;

  @BeforeEach
  void setUp() {
    operations = mock(ConsoleJobOperationsPort.class);
    businessCalendarMapper = mock(BusinessCalendarMapper.class);
    jobDefinitionMapper = mock(JobDefinitionMapper.class);
    managedJobWorkflowReference = mock(ManagedJobWorkflowReferencePort.class);
    service = new DefaultConsoleJobApprovalService(
        operations,
        mock(TriggerInternalRestClient.class),
        mock(ConsoleRequestMetadataResolver.class),
        mock(BatchDayMapper.class),
        businessCalendarMapper,
        jobDefinitionMapper,
        managedJobWorkflowReference);
    when(operations.resolveTenant("tenant-a")).thenReturn("tenant-a");
  }

  @DisplayName("自动批量日补跑使用受管单作业 Workflow")
  @Test
  void shouldAutoBatchDayCatchUpUsesManagedSingleJobWorkflow() {
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "CAL"))
        .thenReturn(Map.of("catchUpPolicy", "AUTO"));
    when(operations.hasText("JOB_A")).thenReturn(true);
    when(operations.parseBizDate("2026-10-14")).thenReturn(LocalDate.parse("2026-10-14"));
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_A"))
        .thenReturn(jobWithWorkflow(42L));
    when(managedJobWorkflowReference.isEnabledSingleJobWorkflow("tenant-a", 42L, 501L))
        .thenReturn(true);
    when(operations.delegateLaunch(any())).thenReturn("run-1");

    service.catchUpBatchDay("2026-10-14", batchDayRequest("JOB_A"), "idem-1");

    ArgumentCaptor<ConsoleLaunchCommand> launchCaptor =
        ArgumentCaptor.forClass(ConsoleLaunchCommand.class);
    verify(operations).delegateLaunch(launchCaptor.capture());
    assertThat(launchCaptor.getValue().workflowDefinitionId()).isEqualTo(42L);
    verify(operations).publishRefresh("tenant-a");
  }

  @DisplayName("人工审批先延期启动并保留解析 Workflow 所需作业身份")
  @Test
  void shouldManualBatchDayApprovalDefersLaunchAndCarriesJobForWorkflowResolution() {
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "CAL"))
        .thenReturn(Map.of("catchUpPolicy", "MANUAL_APPROVAL"));
    when(operations.hasText("JOB_A")).thenReturn(true);
    when(operations.hasText(isNull())).thenReturn(false);
    when(operations.submitApproval(any())).thenReturn("approval-1");

    service.catchUpBatchDay("2026-10-14", batchDayRequest("JOB_A"), "idem-1");

    ArgumentCaptor<ApprovalSubmitContext> approvalCaptor =
        ArgumentCaptor.forClass(ApprovalSubmitContext.class);
    verify(operations).submitApproval(approvalCaptor.capture());
    ConsoleCatchUpApprovalRequest approvalPayload =
        (ConsoleCatchUpApprovalRequest) approvalCaptor.getValue().payload();
    assertThat(approvalPayload.getRequestId()).isNull();
    assertThat(approvalPayload.getJobCode()).isEqualTo("JOB_A");
  }

  @DisplayName("已审批补跑解析 Workflow 根目标")
  @Test
  void shouldApprovedConsoleCatchUpResolvesWorkflowAndDoesNotUseJobLaunchTarget() {
    ConsoleCatchUpApprovalRequest request = new ConsoleCatchUpApprovalRequest();
    request.setTenantId("tenant-a");
    request.setJobCode("JOB_A");
    request.setBizDate("2026-10-14");
    request.setApprovalId("approval-1");
    when(operations.hasText("approval-1")).thenReturn(true);
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_A"))
        .thenReturn(jobWithWorkflow(42L));
    when(managedJobWorkflowReference.isEnabledSingleJobWorkflow("tenant-a", 42L, 501L))
        .thenReturn(true);
    when(operations.delegateLaunch(any())).thenReturn("run-1");

    service.approveCatchUp(request, "idem-1");

    verify(operations).requireApprovedApproval("tenant-a", "approval-1", "CATCH_UP", request);
    ArgumentCaptor<ConsoleLaunchCommand> launchCaptor =
        ArgumentCaptor.forClass(ConsoleLaunchCommand.class);
    verify(operations).delegateLaunch(launchCaptor.capture());
    assertThat(launchCaptor.getValue().workflowDefinitionId()).isEqualTo(42L);
  }

  private BatchDayCatchUpRequest batchDayRequest(String jobCode) {
    BatchDayCatchUpRequest request = new BatchDayCatchUpRequest();
    request.setTenantId("tenant-a");
    request.setCalendarCode("CAL");
    request.setJobCodes(List.of(jobCode));
    return request;
  }

  private JobDefinitionEntity jobWithWorkflow(Long workflowDefinitionId) {
    JobDefinitionEntity job = new JobDefinitionEntity();
    job.setTenantId("tenant-a");
    job.setId(501L);
    job.setJobCode("JOB_A");
    job.setEnabled(true);
    job.setDefaultWorkflowDefinitionId(workflowDefinitionId);
    return job;
  }
}
