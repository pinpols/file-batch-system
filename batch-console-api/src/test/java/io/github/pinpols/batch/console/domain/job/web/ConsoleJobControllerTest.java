package io.github.pinpols.batch.console.domain.job.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.contract.request.WorkflowNodeReplayPreviewRequest;
import io.github.pinpols.batch.console.application.contract.request.WorkflowRunReplayRequest;
import io.github.pinpols.batch.console.application.contract.response.ConsoleWorkflowRunReplayPreviewResponse;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobApprovalService;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobRecoveryService;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleWorkflowRunReplaySubmissionResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.command.CompensationCommandRequest;
import io.github.pinpols.batch.console.shared.command.DeadLetterReplayRequest;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@DisplayName("作业控制器: Job 根触发入口已退役")
@ExtendWith(MockitoExtension.class)
class ConsoleJobControllerTest {

  @Mock
  private ConsoleJobRecoveryService recoveryService;

  @Mock
  private ConsoleJobApprovalService approvalService;

  @Mock
  private ConsoleRequestMetadataResolver requestMetadataResolver;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleJobController(recoveryService, approvalService, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("Job 根触发路由不再注册")
  void shouldRetireJobRootTriggerRoutes() throws Exception {
    mockMvc
        .perform(post("/api/console/jobs/trigger").contentType(APPLICATION_JSON).content("""
                    {"tenantId":"t1","jobCode":"IMPORT_JOB","bizDate":"2026-03-27"}
                    """))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(post("/api/console/jobs/batch-trigger")
            .contentType(APPLICATION_JSON)
            .content("""
                    [{"tenantId":"t1","jobCode":"IMPORT_JOB","bizDate":"2026-03-27"}]
                    """))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("失败 Workflow 节点补偿 API 先登记审批并保留来源运行与节点身份")
  void shouldSubmitApprovalWithExactWorkflowNodeReplayIdentity() throws Exception {
    when(recoveryService.compensation(any(), anyString())).thenReturn("APR-WORKFLOW-NODE");

    mockMvc
        .perform(post("/api/console/jobs/compensations")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-workflow-node")
            .contentType(APPLICATION_JSON)
            .content("""
                    {
                      "tenantId":"t1",
                      "jobCode":"IMPORT_JOB",
                      "bizDate":"2026-03-27",
                      "compensationType":"WORKFLOW_NODE",
                      "targetId":82,
                      "sourceWorkflowRunId":71,
                      "sourceWorkflowNodeRunId":82,
                      "reason":"replay failed node"
                    }
                    """))
        .andExpect(status().isOk());

    ArgumentCaptor<CompensationCommandRequest> requestCaptor =
        ArgumentCaptor.forClass(CompensationCommandRequest.class);
    verify(recoveryService).compensation(requestCaptor.capture(), eq("idem-workflow-node"));
    CompensationCommandRequest request = requestCaptor.getValue();
    assertThat(request.getCompensationType()).isEqualTo("WORKFLOW_NODE");
    assertThat(request.getTargetId()).isEqualTo(82L);
    assertThat(request.getSourceWorkflowRunId()).isEqualTo(71L);
    assertThat(request.getSourceWorkflowNodeRunId()).isEqualTo(82L);
  }

  @Test
  @DisplayName("失败 Workflow JOB 节点预览路由返回审批前冻结预览")
  void shouldRouteWorkflowNodeReplayPreview() throws Exception {
    mockMvc
        .perform(post("/api/console/jobs/workflow-node/replay-preview")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-preview")
            .contentType(APPLICATION_JSON)
            .content("""
                    {
                      "tenantId":"t1",
                      "workflowRunId":71,
                      "workflowNodeRunId":82,
                      "reason":"replay failed node"
                    }
                    """))
        .andExpect(status().isOk());

    ArgumentCaptor<WorkflowNodeReplayPreviewRequest> requestCaptor =
        ArgumentCaptor.forClass(WorkflowNodeReplayPreviewRequest.class);
    verify(recoveryService).previewWorkflowNodeReplay(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getWorkflowRunId()).isEqualTo(71L);
    assertThat(requestCaptor.getValue().getWorkflowNodeRunId()).isEqualTo(82L);
  }

  @Test
  @DisplayName("整 Workflow Replay 预览由 Console API 转发")
  void shouldRouteWholeWorkflowReplayPreview() throws Exception {
    when(recoveryService.previewWorkflowRunReplay(any()))
        .thenReturn(new ConsoleWorkflowRunReplayPreviewResponse(
            "t1",
            71L,
            9L,
            3,
            LocalDate.parse("2026-03-27"),
            "CAL-A",
            null,
            null,
            "CREATE_NEW_VERSION",
            "USE_ORIGINAL_CONFIG",
            null,
            UUID.fromString("44a76b9e-3cc0-46f0-9ac4-16ca44f80684"),
            "preview-token",
            Instant.parse("2026-03-27T00:05:00Z"),
            List.of(),
            List.of()));

    mockMvc
        .perform(post("/api/console/jobs/workflow-run/replay-preview")
            .contentType(APPLICATION_JSON)
            .content("""
                {"tenantId":"t1","workflowRunId":71,"reason":"replay whole workflow"}
                """))
        .andExpect(status().isOk());

    ArgumentCaptor<WorkflowRunReplayRequest> requestCaptor =
        ArgumentCaptor.forClass(WorkflowRunReplayRequest.class);
    verify(recoveryService).previewWorkflowRunReplay(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getWorkflowRunId()).isEqualTo(71L);
  }

  @Test
  @DisplayName("整 Workflow Replay 提交审批时校验并转发稳定幂等键")
  void shouldSubmitWholeWorkflowReplayApproval() throws Exception {
    when(recoveryService.submitWorkflowRunReplay(any(), anyString()))
        .thenReturn(new ConsoleWorkflowRunReplaySubmissionResponse(901L, "APR-WORKFLOW-1"));

    String operationKey = UUID.randomUUID().toString();
    String replayRequestJson = """
                {
                  "tenantId":"t1",
                  "workflowRunId":71,
                  "reason":"replay whole workflow",
                  "operationKey":"%s",
                  "previewToken":"preview-token"
                }
                """.formatted(operationKey);

    mockMvc
        .perform(post("/api/console/jobs/workflow-run/replay-operations")
            .header(
                CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER,
                "44a76b9e-3cc0-46f0-9ac4-16ca44f80684")
            .contentType(APPLICATION_JSON)
            .content(replayRequestJson))
        .andExpect(status().isOk());

    ArgumentCaptor<WorkflowRunReplayRequest> requestCaptor =
        ArgumentCaptor.forClass(WorkflowRunReplayRequest.class);
    verify(recoveryService)
        .submitWorkflowRunReplay(
            requestCaptor.capture(), eq("44a76b9e-3cc0-46f0-9ac4-16ca44f80684"));
    assertThat(requestCaptor.getValue().getOperationKey()).isEqualTo(operationKey);
  }

  @Test
  @DisplayName("外部结果核实重放用认证 Principal 覆盖请求体操作人")
  void shouldUseAuthenticatedPrincipalForExternalOutcomeResolution() throws Exception {
    when(recoveryService.replayDeadLetter(any(), anyString())).thenReturn("APR-DLQ");

    mockMvc
        .perform(post("/api/console/jobs/dead-letters/replay")
            .principal(() -> "authenticated-operator")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-dlq")
            .contentType(APPLICATION_JSON)
            .content("""
                {
                  "tenantId":"t1",
                  "deadLetterId":42,
                  "reason":"external audit verified no action",
                  "operatorId":"untrusted-body-operator",
                  "externalActionVerifiedNotExecuted":true,
                  "externalOutcomeEvidenceReference":"incident://INC-12/evidence-1"
                }
                """))
        .andExpect(status().isOk());

    ArgumentCaptor<DeadLetterReplayRequest> requestCaptor =
        ArgumentCaptor.forClass(DeadLetterReplayRequest.class);
    verify(recoveryService).replayDeadLetter(requestCaptor.capture(), eq("idem-dlq"));
    assertThat(requestCaptor.getValue().getOperatorId()).isEqualTo("authenticated-operator");
  }

  @Test
  @DisplayName("缺少认证 Principal 时拒绝外部结果核实重放")
  void shouldRejectExternalOutcomeResolutionWithoutPrincipal() throws Exception {
    mockMvc
        .perform(post("/api/console/jobs/dead-letters/replay")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-dlq")
            .contentType(APPLICATION_JSON)
            .content("""
                {
                  "tenantId":"t1",
                  "deadLetterId":42,
                  "reason":"external audit verified no action",
                  "operatorId":"body-operator",
                  "externalActionVerifiedNotExecuted":true,
                  "externalOutcomeEvidenceReference":"incident://INC-12/evidence-1"
                }
                """))
        .andExpect(status().isUnauthorized());

    verify(recoveryService, never()).replayDeadLetter(any(), anyString());
  }
}
