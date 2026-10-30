package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.contract.request.WorkflowNodeReplayPreviewRequest;
import io.github.pinpols.batch.console.application.contract.request.WorkflowRunReplayRequest;
import io.github.pinpols.batch.console.application.contract.response.ConsoleWorkflowNodeReplayPreviewResponse;
import io.github.pinpols.batch.console.application.contract.response.ConsoleWorkflowRunReplayPreviewResponse;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobApprovalService;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobRecoveryService;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchDayCatchUpRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.CompensateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.BatchDayCatchUpRequestStatusResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayCatchUpResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleWorkflowRunReplaySubmissionResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.command.CompensationCommandRequest;
import io.github.pinpols.batch.console.shared.command.ConsoleCatchUpApprovalRequest;
import io.github.pinpols.batch.console.shared.command.DeadLetterReplayRequest;
import io.github.pinpols.batch.console.shared.command.PartitionReplayRequest;
import io.github.pinpols.batch.console.shared.command.RerunRequest;
import io.github.pinpols.batch.console.shared.command.TaskReplayRequest;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台作业运维 REST：触发、补偿、重跑、死信回放、Catch-Up 审批。
 *
 * <p>触发作业对所有已认证用户开放（含 ROLE_TENANT_USER），其余运维操作需 ROLE_ADMIN。
 */
@RestController
@Validated
@RequestMapping("/api/console/jobs")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Idempotent
public class ConsoleJobController {

  private final ConsoleJobRecoveryService recoveryService;
  private final ConsoleJobApprovalService approvalService;
  private final ConsoleResponseFactory responseFactory;

  /** 登记补偿命令。 */
  @PostMapping("/compensations")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  public CommonResponse<String> compensation(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody CompensationCommandRequest request) {
    return responseFactory.success(recoveryService.compensation(request, idempotencyKey));
  }

  /** 预览失败 Workflow JOB 节点重放的冻结来源和策略，尚不创建审批或执行。 */
  @PostMapping("/workflow-node/replay-preview")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  public CommonResponse<ConsoleWorkflowNodeReplayPreviewResponse> previewWorkflowNodeReplay(
      @Valid @RequestBody WorkflowNodeReplayPreviewRequest request) {
    return responseFactory.success(recoveryService.previewWorkflowNodeReplay(request));
  }

  /** 提交整 Workflow 重放审批前预览全部节点。 */
  @PostMapping("/workflow-run/replay-preview")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  public CommonResponse<ConsoleWorkflowRunReplayPreviewResponse> previewWorkflowRunReplay(
      @Valid @RequestBody WorkflowRunReplayRequest request) {
    return responseFactory.success(recoveryService.previewWorkflowRunReplay(request));
  }

  /** 持久化重放操作，并创建既有 Console 审批记录。 */
  @PostMapping("/workflow-run/replay-operations")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  public CommonResponse<ConsoleWorkflowRunReplaySubmissionResponse> submitWorkflowRunReplay(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody WorkflowRunReplayRequest request) {
    return responseFactory.success(
        recoveryService.submitWorkflowRunReplay(request, idempotencyKey));
  }

  /** 执行补偿。 */
  @PostMapping("/compensate")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  public CommonResponse<String> compensate(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody CompensateRequest request) {
    return responseFactory.success(recoveryService.compensate(request, idempotencyKey));
  }

  /** 重跑实例或分区。 */
  @PostMapping("/rerun")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  public CommonResponse<String> rerun(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody RerunRequest request) {
    return responseFactory.success(recoveryService.rerun(request, idempotencyKey));
  }

  /** 死信重放。 */
  @PostMapping("/dead-letters/replay")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  public CommonResponse<String> replayDeadLetter(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody DeadLetterReplayRequest request,
      Principal principal) {
    if (EmptyChecks.isNull(principal)
        && Boolean.TRUE.equals(request.getExternalActionVerifiedNotExecuted())) {
      throw BizException.of(ResultCode.UNAUTHORIZED, "error.common.unauthorized_detail");
    }
    if (EmptyChecks.isNotNull(principal)) {
      request.setOperatorId(authenticatedUsername(principal));
    }
    return responseFactory.success(recoveryService.replayDeadLetter(request, idempotencyKey));
  }

  private static String authenticatedUsername(Principal principal) {
    if (principal instanceof Authentication authentication
        && authentication.getPrincipal() instanceof ConsolePrincipal consolePrincipal) {
      return consolePrincipal.username();
    }
    return principal.getName();
  }

  /** 任务重放（job_task 粒度）。 */
  @PostMapping("/tasks/replay")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  public CommonResponse<String> replayTask(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody TaskReplayRequest request) {
    return responseFactory.success(recoveryService.replayTask(request, idempotencyKey));
  }

  /** 分区重放（job_partition 粒度）。 */
  @PostMapping("/partitions/replay")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  public CommonResponse<String> replayPartition(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody PartitionReplayRequest request) {
    return responseFactory.success(recoveryService.replayPartition(request, idempotencyKey));
  }

  /** 审批通过 Catch-Up 请求。 */
  @PostMapping("/catch-up/approve")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  public CommonResponse<String> approveCatchUp(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody ConsoleCatchUpApprovalRequest request) {
    return responseFactory.success(approvalService.approveCatchUp(request, idempotencyKey));
  }

  /** 按批量日发起 catch-up。 */
  @PostMapping("/batch-days/{bizDate}/catchup")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  public CommonResponse<ConsoleBatchDayCatchUpResponse> batchDayCatchUp(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable String bizDate,
      @Valid @RequestBody BatchDayCatchUpRequest request) {
    return responseFactory.success(
        approvalService.catchUpBatchDay(bizDate, request, idempotencyKey));
  }

  /** 按原请求键读取结果；未找到不代表未执行。 */
  @GetMapping("/batch-days/catchup/requests/status")
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
  public CommonResponse<BatchDayCatchUpRequestStatusResponse> batchDayCatchUpStatus(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) @NotBlank String requestKey,
      @RequestParam("tenantId") @NotBlank String tenantId) {
    return responseFactory.success(approvalService.batchDayCatchUpStatus(tenantId, requestKey));
  }
}
