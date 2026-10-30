package io.github.pinpols.batch.console.domain.job.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.ops.ConsoleJobOperationsPort;
import io.github.pinpols.batch.console.application.workflow.ManagedJobWorkflowReferencePort;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchDayCatchUpRequest;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchDayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.ActiveCalendarRow;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.shared.client.TriggerInternalRestClient;
import io.github.pinpols.batch.console.shared.command.ApprovalSubmitContext;
import io.github.pinpols.batch.console.shared.command.ConsoleCatchUpApprovalRequest;
import io.github.pinpols.batch.console.shared.command.ConsoleLaunchCommand;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

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
    ConsoleRequestMetadataResolver metadata = mock(ConsoleRequestMetadataResolver.class);
    when(metadata.current())
        .thenReturn(new ConsoleRequestMetadata("req", "trace", "tenant-a", "op", null, null));
    service = new DefaultConsoleJobApprovalService(
        operations,
        mock(TriggerInternalRestClient.class),
        metadata,
        mock(BatchDayMapper.class),
        businessCalendarMapper,
        jobDefinitionMapper,
        managedJobWorkflowReference,
        new InMemoryBatchDayCatchUpStore(),
        mock(OrchestratorApprovalClient.class));
    when(operations.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(operations.parseBizDate("2026-10-14")).thenReturn(LocalDate.parse("2026-10-14"));
    when(jobDefinitionMapper.selectByUniqueKey(any(), any())).thenAnswer(call -> {
      String jobCode = call.getArgument(1);
      return "MISSING".equals(jobCode) ? null : jobWithWorkflow(42L);
    });
    when(managedJobWorkflowReference.isEnabledSingleJobWorkflow("tenant-a", 42L, 501L))
        .thenReturn(true);
  }

  @DisplayName("自动批量日补跑使用受管单作业 Workflow")
  @Test
  void shouldAutoBatchDayCatchUpUsesManagedSingleJobWorkflow() {
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "CAL"))
        .thenReturn(activeCalendar("AUTO"));
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

  @Test
  @DisplayName("自动补跑项目键不超过 Trigger 存储上限并保留原请求身份")
  void shouldBoundAutoCatchUpItemKey() {
    String jobCode = "J".repeat(128);
    String shortKey = "s".repeat(127);
    String longKey = "l".repeat(128);
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "CAL"))
        .thenReturn(activeCalendar("AUTO"));
    when(operations.hasText(jobCode)).thenReturn(true);
    when(operations.delegateLaunch(any())).thenReturn("request-1");
    service.catchUpBatchDay("2026-10-14", batchDayRequest(jobCode), shortKey);
    service.catchUpBatchDay("2026-10-14", batchDayRequest(jobCode), longKey);
    service.catchUpBatchDay("2026-10-14", batchDayRequest(jobCode), longKey);
    service.catchUpBatchDay("2026-10-14", batchDayRequest(jobCode), "m".repeat(128));
    var captor = ArgumentCaptor.forClass(ConsoleLaunchCommand.class);
    verify(operations, Mockito.times(3)).delegateLaunch(captor.capture());
    assertThat(captor.getAllValues().getFirst().idempotencyKey())
        .isEqualTo(shortKey + ":" + jobCode)
        .hasSize(256);
    assertThat(captor.getAllValues().get(1).idempotencyKey())
        .isEqualTo(
            "batchday-auto-f54ccaccb19d2335f49108453d0af28fda45c959737b15b75a86e6e0176ace01");
    assertThat(captor.getAllValues().getLast().idempotencyKey())
        .hasSizeLessThanOrEqualTo(256)
        .isNotEqualTo(captor.getAllValues().get(1).idempotencyKey());
  }

  @Test
  @DisplayName("自动补跑先校验所有作业，后续作业不存在时不启动前一项")
  void shouldValidateAllAutoTargetsBeforeStartingAnyJob() {
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "CAL"))
        .thenReturn(activeCalendar("AUTO"));
    when(operations.hasText("JOB_A")).thenReturn(true);
    when(operations.hasText("MISSING")).thenReturn(true);
    when(operations.parseBizDate("2026-10-14")).thenReturn(LocalDate.parse("2026-10-14"));
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_A"))
        .thenReturn(jobWithWorkflow(42L));
    when(managedJobWorkflowReference.isEnabledSingleJobWorkflow("tenant-a", 42L, 501L))
        .thenReturn(true);
    BatchDayCatchUpRequest request = batchDayRequest("JOB_A");
    request.setJobCodes(List.of("JOB_A", "MISSING"));

    assertThatThrownBy(() -> service.catchUpBatchDay("2026-10-14", request, "idem-1"))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.NOT_FOUND));
    verify(operations, never()).delegateLaunch(any());
  }

  @Test
  @DisplayName("合法批次按输入顺序提交并保留每项幂等键")
  void shouldPreserveLaunchOrderAndKeysAfterPrevalidation() {
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "CAL"))
        .thenReturn(activeCalendar("AUTO"));
    when(operations.hasText("JOB_A")).thenReturn(true);
    when(operations.hasText("JOB_B")).thenReturn(true);
    when(operations.parseBizDate("2026-10-14")).thenReturn(LocalDate.parse("2026-10-14"));
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_A"))
        .thenReturn(jobWithWorkflow(42L));
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_B"))
        .thenReturn(jobWithWorkflow(43L));
    when(managedJobWorkflowReference.isEnabledSingleJobWorkflow("tenant-a", 42L, 501L))
        .thenReturn(true);
    when(managedJobWorkflowReference.isEnabledSingleJobWorkflow("tenant-a", 43L, 501L))
        .thenReturn(true);
    when(operations.delegateLaunch(any())).thenReturn("run-a", "run-b");
    BatchDayCatchUpRequest request = batchDayRequest("JOB_A");
    request.setJobCodes(List.of("JOB_A", "JOB_B"));

    var response = service.catchUpBatchDay("2026-10-14", request, "idem-1");

    ArgumentCaptor<ConsoleLaunchCommand> launches =
        ArgumentCaptor.forClass(ConsoleLaunchCommand.class);
    verify(operations, Mockito.times(2)).delegateLaunch(launches.capture());
    assertThat(launches.getAllValues())
        .extracting(ConsoleLaunchCommand::idempotencyKey)
        .containsExactly("idem-1:JOB_A", "idem-1:JOB_B");
    assertThat(launches.getAllValues())
        .extracting(ConsoleLaunchCommand::workflowDefinitionId)
        .containsExactly(42L, 43L);
    assertThat(response.items()).hasSize(2);
  }

  @Test
  @DisplayName("人工补跑遇到无效后续作业时不创建前一项审批")
  void shouldValidateAllManualTargetsBeforeCreatingAnyApproval() {
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "CAL"))
        .thenReturn(activeCalendar("MANUAL_APPROVAL"));
    when(operations.hasText("JOB_A")).thenReturn(true);
    when(operations.hasText("MISSING")).thenReturn(true);
    BatchDayCatchUpRequest request = batchDayRequest("JOB_A");
    request.setJobCodes(List.of("JOB_A", "MISSING"));
    assertThatThrownBy(() -> service.catchUpBatchDay("2026-10-14", request, "idem-1"))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.NOT_FOUND));
    verify(operations, never()).submitApproval(any());
  }

  @Test
  @DisplayName("同键不同请求拒绝复用原批次结果")
  void shouldRejectDifferentBatchRequestForSameKey() {
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "CAL"))
        .thenReturn(activeCalendar("MANUAL_APPROVAL"));
    when(operations.hasText("JOB_A")).thenReturn(true);
    when(operations.hasText("JOB_B")).thenReturn(true);
    when(operations.submitApproval(any())).thenReturn("approval-1");
    service.catchUpBatchDay("2026-10-14", batchDayRequest("JOB_A"), "idem-1");
    assertThatThrownBy(
            () -> service.catchUpBatchDay("2026-10-14", batchDayRequest("JOB_B"), "idem-1"))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT));
    verify(operations).submitApproval(any());
  }

  @Test
  @DisplayName("日历关闭补跑时拒绝创建审批或触发请求")
  void shouldRejectBatchDayCatchUpWhenCalendarPolicyIsNone() {
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "CAL"))
        .thenReturn(activeCalendar("NONE"));
    when(operations.hasText("JOB_A")).thenReturn(true);
    when(operations.parseBizDate("2026-10-14")).thenReturn(LocalDate.parse("2026-10-14"));

    assertThatThrownBy(
            () -> service.catchUpBatchDay("2026-10-14", batchDayRequest("JOB_A"), "idem-1"))
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT);
          assertThat(failure.getMessageKey()).isEqualTo("error.batch_day.catch_up_disabled");
        });
    verify(operations, never()).submitApproval(any());
    verify(operations, never()).delegateLaunch(any());
  }

  @DisplayName("人工审批先延期启动并保留解析 Workflow 所需作业身份")
  @Test
  void shouldManualBatchDayApprovalDefersLaunchAndCarriesJobForWorkflowResolution() {
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "CAL"))
        .thenReturn(activeCalendar("MANUAL_APPROVAL"));
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

  @Test
  @DisplayName("已创建审批的原请求重试不依赖当前作业配置")
  void shouldRetryExistingManualApprovalWhenJobIsDisabledAfterSubmission() {
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "CAL"))
        .thenReturn(activeCalendar("MANUAL_APPROVAL"));
    when(operations.hasText("JOB_A")).thenReturn(true);
    when(operations.submitApproval(any())).thenReturn("approval-1");
    JobDefinitionEntity enabled = jobWithWorkflow(42L);
    JobDefinitionEntity disabled = jobWithWorkflow(42L);
    disabled.setEnabled(false);
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_A")).thenReturn(enabled, disabled);

    var first = service.catchUpBatchDay("2026-10-14", batchDayRequest("JOB_A"), "idem-1");
    var retry = service.catchUpBatchDay("2026-10-14", batchDayRequest("JOB_A"), "idem-1");

    assertThat(retry.items().getFirst().referenceNo())
        .isEqualTo(first.items().getFirst().referenceNo());
  }

  @Test
  @DisplayName("人工批量补跑的长作业编码可重复提交且审批身份和载荷稳定")
  void shouldKeepLongManualBatchDayApprovalIdentityAndPayloadStable() {
    String jobCode = "J".repeat(128);
    String requestKey = "12345678-1234-1234-1234-123456789012";
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "CAL"))
        .thenReturn(activeCalendar("MANUAL_APPROVAL"));
    when(operations.hasText(jobCode)).thenReturn(true);
    when(operations.submitApproval(any())).thenReturn("approval-1");

    var firstResponse = service.catchUpBatchDay("2026-10-14", batchDayRequest(jobCode), requestKey);
    var retryResponse = service.catchUpBatchDay("2026-10-14", batchDayRequest(jobCode), requestKey);
    assertThat(retryResponse).isEqualTo(firstResponse);

    ArgumentCaptor<ApprovalSubmitContext> approvals =
        ArgumentCaptor.forClass(ApprovalSubmitContext.class);
    verify(operations).submitApproval(approvals.capture());
    ApprovalSubmitContext first = approvals.getAllValues().get(0);
    assertThat(first.idempotencyKey()).hasSizeLessThanOrEqualTo(128);
    assertThat(first.idempotencyKey())
        .isEqualTo("batchday-8c32e74419490ac8347867a8dca66011689fdc4aff0230666cc318943bec9387");
    String expectedPayload = """
        {"tenantId":"tenant-a","requestId":null,"workflowDefinitionId":null,
         "jobCode":"%s","bizDate":"2026-10-14","batchDayCalendarCode":"CAL",
         "scheduledAt":null,"reason":null,"approvalId":null}
        """.formatted(jobCode);
    assertThat(JsonUtils.fromJson(JsonUtils.toJson(first.payload()), JsonNode.class))
        .isEqualTo(JsonUtils.fromJson(expectedPayload, JsonNode.class));
  }

  @Test
  @DisplayName("相同批量日人工补跑请求的审批载荷不随提交时间改变")
  void shouldKeepManualBatchDayApprovalPayloadStableOnRetry() {
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "CAL"))
        .thenReturn(activeCalendar("MANUAL_APPROVAL"));
    when(operations.hasText("JOB_A")).thenReturn(true);
    when(operations.submitApproval(any())).thenReturn("approval-1");

    var first = service.catchUpBatchDay("2026-10-14", batchDayRequest("JOB_A"), "idem-1");
    var retry = service.catchUpBatchDay("2026-10-14", batchDayRequest("JOB_A"), "idem-1");

    ArgumentCaptor<ApprovalSubmitContext> approvals =
        ArgumentCaptor.forClass(ApprovalSubmitContext.class);
    verify(operations).submitApproval(approvals.capture());
    assertThat(retry).isEqualTo(first);
  }

  @Test
  @DisplayName("长审批键区分作业和请求，短审批键保留原格式")
  void shouldDistinguishLongManualApprovalKeysAndPreserveShortKey() {
    String jobA = "A".repeat(128);
    String jobB = "B".repeat(128);
    String requestKey = "12345678-1234-1234-1234-123456789012";
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "CAL"))
        .thenReturn(activeCalendar("MANUAL_APPROVAL"));
    when(operations.hasText(jobA)).thenReturn(true);
    when(operations.hasText(jobB)).thenReturn(true);
    when(operations.hasText("JOB_A")).thenReturn(true);
    when(operations.submitApproval(any())).thenReturn("approval-1");

    BatchDayCatchUpRequest both = batchDayRequest(jobA);
    both.setJobCodes(List.of(jobA, jobB));
    service.catchUpBatchDay("2026-10-14", both, requestKey);
    service.catchUpBatchDay("2026-10-14", batchDayRequest(jobA), requestKey + "-other");
    service.catchUpBatchDay("2026-10-14", batchDayRequest("JOB_A"), "idem-1");

    ArgumentCaptor<ApprovalSubmitContext> approvals =
        ArgumentCaptor.forClass(ApprovalSubmitContext.class);
    verify(operations, Mockito.times(4)).submitApproval(approvals.capture());
    List<String> keys = approvals.getAllValues().stream()
        .map(ApprovalSubmitContext::idempotencyKey)
        .toList();
    assertThat(keys).doesNotHaveDuplicates();
    assertThat(keys.subList(0, 3)).allSatisfy(key -> assertThat(key).hasSizeLessThanOrEqualTo(128));
    assertThat(keys.get(3)).isEqualTo("idem-1:JOB_A");
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

  @Test
  @DisplayName("批日人工审批载荷和批准后触发快照保留日历及作业身份")
  void shouldPreserveBatchDayIdentityAcrossManualApproval() {
    when(businessCalendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "CAL"))
        .thenReturn(activeCalendar("MANUAL_APPROVAL"));
    when(operations.hasText("JOB_A")).thenReturn(true);
    when(operations.hasText("approval-1")).thenReturn(true);
    when(operations.hasText("CAL")).thenReturn(true);
    when(operations.submitApproval(any())).thenReturn("approval-1");
    when(jobDefinitionMapper.selectByUniqueKey("tenant-a", "JOB_A"))
        .thenReturn(jobWithWorkflow(42L));
    when(managedJobWorkflowReference.isEnabledSingleJobWorkflow("tenant-a", 42L, 501L))
        .thenReturn(true);
    when(operations.delegateLaunch(any())).thenReturn("run-1");

    service.catchUpBatchDay("2026-10-14", batchDayRequest("JOB_A"), "idem-1");
    ArgumentCaptor<ApprovalSubmitContext> approval =
        ArgumentCaptor.forClass(ApprovalSubmitContext.class);
    verify(operations).submitApproval(approval.capture());
    String persistedPayload = JsonUtils.toJson(approval.getValue().payload());
    assertThat(JsonUtils.fromJson(persistedPayload, JsonNode.class)
            .path("batchDayCalendarCode")
            .asText())
        .isEqualTo("CAL");
    ConsoleCatchUpApprovalRequest approved =
        JsonUtils.fromJsonStrict(persistedPayload, ConsoleCatchUpApprovalRequest.class);
    approved.setApprovalId("approval-1");

    service.approveCatchUp(approved, "approval-1");

    verify(operations).requireApprovedApproval("tenant-a", "approval-1", "CATCH_UP", approved);
    ArgumentCaptor<ConsoleLaunchCommand> launch =
        ArgumentCaptor.forClass(ConsoleLaunchCommand.class);
    verify(operations).delegateLaunch(launch.capture());
    assertThat(launch.getValue().params())
        .containsEntry("batchDayCalendarCode", "CAL")
        .containsEntry("jobCode", "JOB_A")
        .containsEntry("batchDayBizDate", "2026-10-14");
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

  private static ActiveCalendarRow activeCalendar(String catchUpPolicy) {
    return new ActiveCalendarRow(
        1L, "tenant-a", "CAL", null, null, catchUpPolicy, null, null, null);
  }
}
