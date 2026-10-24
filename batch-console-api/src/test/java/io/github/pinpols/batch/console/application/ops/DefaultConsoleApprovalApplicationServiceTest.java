package io.github.pinpols.batch.console.application.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFileApplicationService;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobApprovalService;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobRecoveryService;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient.ApprovalDecision;
import io.github.pinpols.batch.console.shared.command.DeadLetterReplayRequest;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("审批应用服务: 按动作类型委派到对应作业服务")
class DefaultConsoleApprovalApplicationServiceTest {

  private static final String TENANT_ID = "tenant-a";

  private OrchestratorInternalRestClient orchestratorClient;
  private ConsoleJobRecoveryService recoveryService;
  private ConsoleJobApprovalService approvalService;
  private DefaultConsoleApprovalApplicationService service;

  @BeforeEach
  void setUp() {
    orchestratorClient = mock(OrchestratorInternalRestClient.class);
    ConsoleRequestMetadataResolver metadataResolver = mock(ConsoleRequestMetadataResolver.class);
    recoveryService = mock(ConsoleJobRecoveryService.class);
    approvalService = mock(ConsoleJobApprovalService.class);
    service = new DefaultConsoleApprovalApplicationService(
        orchestratorClient,
        metadataResolver,
        recoveryService,
        approvalService,
        mock(ConsoleFileApplicationService.class));
    when(metadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", TENANT_ID, "operator", null, null));
  }

  @Test
  @DisplayName("补偿、重跑、DLQ 重放和 catch-up 审批委派到各自的窄服务")
  void shouldDispatchApprovalActionsToDedicatedServices() {
    List<String> records = List.of(
        approval("COMPENSATION", "JOB_INSTANCE"),
        approval("RERUN", "JOB_INSTANCE"),
        approval("DLQ_REPLAY", "DLQ"),
        approval("CATCH_UP", "JOB_INSTANCE"));
    AtomicInteger index = new AtomicInteger();
    when(orchestratorClient.loadApproval(eq(TENANT_ID), any(), any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      Class<Object> responseType = (Class<Object>) invocation.getArgument(2);
      return JsonUtils.fromJson(records.get(index.getAndIncrement()), responseType);
    });
    for (int i = 0; i < records.size(); i++) {
      String approvalNo = "approval-" + (i + 1);
      String approvalJson = records.get(i);
      ApprovalDecision decision = remoteDecision(approvalNo, "APPROVED", approvalJson, "operator");
      when(orchestratorClient.approveApproval(eq(TENANT_ID), eq(approvalNo), any(), any(), any(), any()))
          .thenReturn(decision);
    }
    when(recoveryService.compensation(any(), eq("approval-1"))).thenReturn("compensated");
    when(recoveryService.rerun(any(), eq("approval-2"))).thenReturn("rerun");
    when(recoveryService.replayDeadLetter(any(), eq("approval-3"))).thenReturn("replayed");
    when(approvalService.approveCatchUp(any(), eq("approval-4"))).thenReturn("caught-up");

    assertThat(service.approve(TENANT_ID, "approval-1", "operator", "approve"))
        .isEqualTo("compensated");
    assertThat(service.approve(TENANT_ID, "approval-2", "operator", "approve")).isEqualTo("rerun");
    assertThat(service.approve(TENANT_ID, "approval-3", "operator", "approve"))
        .isEqualTo("replayed");
    assertThat(service.approve(TENANT_ID, "approval-4", "operator", "approve"))
        .isEqualTo("caught-up");

    verify(recoveryService).compensation(any(), eq("approval-1"));
    verify(recoveryService).rerun(any(), eq("approval-2"));
    ArgumentCaptor<DeadLetterReplayRequest> replayRequest =
        ArgumentCaptor.forClass(DeadLetterReplayRequest.class);
    verify(recoveryService).replayDeadLetter(replayRequest.capture(), eq("approval-3"));
    assertThat(replayRequest.getValue().getOperatorId()).isEqualTo("operator");
    verify(approvalService).approveCatchUp(any(), eq("approval-4"));
  }

  @Test
  @DisplayName("Workflow 节点重放审批拒绝后将父 Replay Operation 收敛为已拒绝")
  void shouldRejectWorkflowNodeReplayOperationWithApproval() {
    String payloadJson = JsonUtils.toJson(Map.of(
        "compensationType", "WORKFLOW_NODE",
        "replayOperationId", 45L));
    String approvalJson = JsonUtils.toJson(Map.of(
        "record", Map.of(
            "approvalStatus", "PENDING",
            "actionType", "COMPENSATION",
            "payloadJson", payloadJson)));
    when(orchestratorClient.loadApproval(eq(TENANT_ID), eq("approval-node"), any()))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          Class<Object> responseType = (Class<Object>) invocation.getArgument(2);
          return JsonUtils.fromJson(approvalJson, responseType);
        });

    ApprovalDecision decision = remoteDecision("approval-node", "REJECTED", approvalJson, "operator");
    when(orchestratorClient.rejectApproval(eq(TENANT_ID), eq("approval-node"), any(), any(), any(), any()))
        .thenReturn(decision);

    assertThat(service.reject(TENANT_ID, "approval-node", "operator", "not approved"))
        .isEqualTo("approval-node");

    verify(orchestratorClient).rejectWorkflowNodeReplayOperation(45L, TENANT_ID);
  }

  @Test
  @DisplayName("整 Workflow Replay 审批从认证操作人启动被冻结的 Replay Operation")
  void shouldApproveWholeWorkflowReplayOperationWithAuthenticatedActor() {
    String payloadJson = JsonUtils.toJson(Map.of(
        "tenantId", TENANT_ID,
        "replayOperationId", 46L));
    String approvalJson = JsonUtils.toJson(Map.of(
        "record", Map.of(
            "approvalStatus", "PENDING",
            "actionType", "WORKFLOW_RUN_REPLAY",
            "payloadJson", payloadJson)));
    when(orchestratorClient.loadApproval(eq(TENANT_ID), eq("approval-workflow"), any()))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          Class<Object> responseType = (Class<Object>) invocation.getArgument(2);
          return JsonUtils.fromJson(approvalJson, responseType);
        });

    ApprovalDecision decision = remoteDecision(
        "approval-workflow", "APPROVED", approvalJson, "authenticated-approver");
    when(orchestratorClient.approveApproval(eq(TENANT_ID), eq("approval-workflow"), any(), any(), any(), any()))
        .thenReturn(decision);

    assertThat(service.approve(TENANT_ID, "approval-workflow", "authenticated-approver", "approve"))
        .isEqualTo("approval-workflow");

    verify(orchestratorClient).approveWorkflowRunReplayOperation(
        46L, TENANT_ID, "authenticated-approver");
    verify(orchestratorClient).markApprovalExecuted(TENANT_ID, "approval-workflow");
  }

  @Test
  @DisplayName("整 Workflow Replay 审批拒绝将父 operation 标记为 REJECTED")
  void shouldRejectWholeWorkflowReplayOperationWithApproval() {
    String payloadJson = JsonUtils.toJson(Map.of(
        "tenantId", TENANT_ID,
        "replayOperationId", 47L));
    String approvalJson = JsonUtils.toJson(Map.of(
        "record", Map.of(
            "approvalStatus", "PENDING",
            "actionType", "WORKFLOW_RUN_REPLAY",
            "payloadJson", payloadJson)));
    when(orchestratorClient.loadApproval(eq(TENANT_ID), eq("approval-workflow-rejected"), any()))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          Class<Object> responseType = (Class<Object>) invocation.getArgument(2);
          return JsonUtils.fromJson(approvalJson, responseType);
        });

    ApprovalDecision decision = remoteDecision(
        "approval-workflow-rejected", "REJECTED", approvalJson, "authenticated-approver");
    when(orchestratorClient.rejectApproval(eq(TENANT_ID), eq("approval-workflow-rejected"), any(), any(), any(), any()))
        .thenReturn(decision);

    assertThat(service.reject(
            TENANT_ID, "approval-workflow-rejected", "authenticated-approver", "reject"))
        .isEqualTo("approval-workflow-rejected");

    verify(orchestratorClient).rejectWorkflowRunReplayOperation(47L, TENANT_ID);
  }

  @Test
  @DisplayName("首次查询待审批但远端批准实际返回已拒绝时，不启动整 Workflow Replay")
  void shouldNotStartWholeWorkflowReplay_whenRemoteApprovalActuallyRejected() {
    String approvalNo = "approval-workflow-raced";
    stubPendingWholeWorkflowApproval(approvalNo);
    RestClient.Builder builder = RestClient.builder().baseUrl("https://approval-race.invalid");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClient client = builder.build();
    when(orchestratorClient.client()).thenReturn(client);
    doCallRealMethod().when(orchestratorClient).approveApproval(
        TENANT_ID, approvalNo, "operator", "approve", "req-1", "trace-1");
    String actualDecision = wholeWorkflowApprovalJson(approvalNo, "REJECTED");
    server.expect(requestTo("https://approval-race.invalid/internal/approvals/approval-workflow-raced/approve"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(actualDecision, MediaType.APPLICATION_JSON));

    Throwable failure = catchThrowable(() -> service.approve(TENANT_ID, approvalNo, "operator", "approve"));

    server.verify();
    verify(orchestratorClient, never()).approveWorkflowRunReplayOperation(any(), any(), any());
    verify(orchestratorClient, never()).markApprovalExecuted(any(), any());
    assertThat(failure).isInstanceOf(BizException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"APPROVED", "EXECUTED"})
  @DisplayName("首次查询待审批但远端拒绝输给已批准决策时，不拒绝 Replay Operation")
  void shouldNotRejectWholeWorkflowReplay_whenRemoteRejectionLosesDecision(String actualStatus) {
    String approvalNo = "approval-workflow-raced";
    stubPendingWholeWorkflowApproval(approvalNo);
    RestClient.Builder builder = RestClient.builder().baseUrl("https://approval-race.invalid");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClient client = builder.build();
    when(orchestratorClient.client()).thenReturn(client);
    doCallRealMethod().when(orchestratorClient).rejectApproval(
        TENANT_ID, approvalNo, "operator", "reject", "req-1", "trace-1");
    String actualDecision = wholeWorkflowApprovalJson(approvalNo, actualStatus);
    server.expect(requestTo("https://approval-race.invalid/internal/approvals/approval-workflow-raced/reject"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(actualDecision, MediaType.APPLICATION_JSON));

    Throwable failure = catchThrowable(() -> service.reject(TENANT_ID, approvalNo, "operator", "reject"));

    server.verify();
    verify(orchestratorClient, never()).rejectWorkflowRunReplayOperation(any(), any());
    verify(orchestratorClient, never()).markApprovalExecuted(any(), any());
    assertThat(failure).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("批准响应已执行表示原调用已完成，不再次派发 Replay Operation")
  void shouldNotRedispatchWholeWorkflowReplay_whenApprovalResponseAlreadyExecuted() {
    String approvalNo = "approval-workflow-raced";
    stubPendingWholeWorkflowApproval(approvalNo);
    RestClient.Builder builder = RestClient.builder().baseUrl("https://approval-race.invalid");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClient client = builder.build();
    when(orchestratorClient.client()).thenReturn(client);
    doCallRealMethod().when(orchestratorClient).approveApproval(
        TENANT_ID, approvalNo, "operator", "approve", "req-1", "trace-1");
    String actualDecision = wholeWorkflowApprovalJson(approvalNo, "EXECUTED");
    server.expect(requestTo("https://approval-race.invalid/internal/approvals/approval-workflow-raced/approve"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(actualDecision, MediaType.APPLICATION_JSON));

    String result = service.approve(TENANT_ID, approvalNo, "operator", "approve");

    server.verify();
    assertThat(result).isEqualTo(approvalNo);
    verify(orchestratorClient, never()).approveWorkflowRunReplayOperation(any(), any(), any());
    verify(orchestratorClient, never()).markApprovalExecuted(any(), any());
  }

  @Test
  @DisplayName("已批准审批失回应答后由另一管理员重试，Replay仍保留实际批准人")
  void shouldResumeApprovedWholeWorkflowReplayWithPersistedApprover() {
    String approvalNo = "approval-workflow-retry";
    String approvedJson = wholeWorkflowApprovalJson(approvalNo, "APPROVED");
    when(orchestratorClient.loadApproval(eq(TENANT_ID), eq(approvalNo), any()))
        .thenAnswer(invocation -> {
          Class<?> responseType = invocation.getArgument(2);
          return JsonUtils.fromJson(approvedJson, responseType);
        });

    String result = service.approve(TENANT_ID, approvalNo, "retrying-admin", "resume");

    assertThat(result).isEqualTo(approvalNo);
    verify(orchestratorClient).approveWorkflowRunReplayOperation(46L, TENANT_ID, "operator");
    verify(orchestratorClient).markApprovalExecuted(TENANT_ID, approvalNo);
    verify(orchestratorClient, never()).approveApproval(any(), any(), any(), any(), any(), any());
  }

  private static ApprovalDecision remoteDecision(
      String approvalNo, String status, String approvalJson, String approver) {
    Map<?, ?> response = JsonUtils.fromJson(approvalJson, Map.class);
    Map<?, ?> record = (Map<?, ?>) response.get("record");
    ApprovalDecision decision = ApprovalDecision.builder()
        .tenantId(TENANT_ID)
        .approvalNo(approvalNo)
        .approvalStatus(status)
        .actionType((String) record.get("actionType"))
        .targetType((String) record.get("targetType"))
        .targetId((String) record.get("targetId"))
        .payloadJson((String) record.get("payloadJson"))
        .approverId(approver)
        .sourceIdempotencyKey((String) record.get("sourceIdempotencyKey"))
        .build();
    return decision;
  }

  private void stubPendingWholeWorkflowApproval(String approvalNo) {
    String pendingJson = wholeWorkflowApprovalJson(approvalNo, "PENDING");
    when(orchestratorClient.loadApproval(eq(TENANT_ID), eq(approvalNo), any()))
        .thenAnswer(invocation -> {
          Class<?> responseType = invocation.getArgument(2);
          return JsonUtils.fromJson(pendingJson, responseType);
        });
  }

  private static String wholeWorkflowApprovalJson(String approvalNo, String status) {
    Map<String, Object> payload = Map.of("tenantId", TENANT_ID, "replayOperationId", 46L);
    String payloadJson = JsonUtils.toJson(payload);
    Map<String, Object> record = Map.of(
        "tenantId", TENANT_ID,
        "approvalNo", approvalNo,
        "approvalType", "COMPENSATION",
        "actionType", "WORKFLOW_RUN_REPLAY",
        "targetType", "WORKFLOW_RUN",
        "targetId", "901",
        "payloadJson", payloadJson,
        "approvalStatus", status,
        "approverId", "operator",
        "sourceIdempotencyKey", "7c51d5c7-afc4-4016-b8cf-1888939c1269");
    return JsonUtils.toJson(Map.of("record", record));
  }

  private static String approval(String actionType, String targetType) {
    return """
        {"record":{"approvalStatus":"PENDING","actionType":"%s","targetType":"%s","payloadJson":"{}"}}
        """.formatted(actionType, targetType);
  }
}
