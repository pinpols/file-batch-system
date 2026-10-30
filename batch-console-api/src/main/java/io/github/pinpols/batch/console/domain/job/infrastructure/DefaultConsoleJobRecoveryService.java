package io.github.pinpols.batch.console.domain.job.infrastructure;

import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.contract.request.WorkflowNodeReplayPreviewRequest;
import io.github.pinpols.batch.console.application.contract.request.WorkflowRunReplayRequest;
import io.github.pinpols.batch.console.application.contract.response.ConsoleWorkflowNodeReplayPreviewResponse;
import io.github.pinpols.batch.console.application.contract.response.ConsoleWorkflowRunReplayPreviewResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleJobOperationsPort;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobRecoveryService;
import io.github.pinpols.batch.console.domain.job.application.contract.request.CompensateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleWorkflowRunReplaySubmissionResponse;
import io.github.pinpols.batch.console.shared.command.ApprovalSubmitContext;
import io.github.pinpols.batch.console.shared.command.CompensationCommandRequest;
import io.github.pinpols.batch.console.shared.command.CompensationPayload;
import io.github.pinpols.batch.console.shared.command.DeadLetterReplayRequest;
import io.github.pinpols.batch.console.shared.command.PartitionReplayRequest;
import io.github.pinpols.batch.console.shared.command.RerunRequest;
import io.github.pinpols.batch.console.shared.command.TaskReplayRequest;
import java.time.LocalDate;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 控制台作业恢复入口：补偿（compensation/compensate）、重跑（rerun）、死信重放、分区重放、任务重放 5 类。
 *
 * <p>关键模式：
 *
 * <ul>
 *   <li><b>审批两阶段</b>（compensation / replayDeadLetter / replayTask / replayPartition）：请求未带 {@code
 *       approvalId} 时先发起审批 → 返回 approvalNo，前端把它带回来再调用本接口；带 {@code approvalId} 时先 {@code
 *       requireApprovedApproval} 校验审批通过态才真正提交。这样保证敏感恢复操作必须经过审批流 而不能直连触发（{@code compensate} / {@code
 *       rerun} 除外——它们假定调用方已自行鉴权）。
 *   <li><b>事件广播</b>：每次成功操作都 {@code publishRefresh(tenantId)} 让前端视图立即刷新，避免用户重复提交。
 *   <li><b>文本清洗</b>：所有自由文本入参（reason / operatorId / approvalId / strategy 等）统一经 {@link
 *       io.github.pinpols.batch.common.utils.ConsoleTextSanitizer#safeInput} 截断并过滤控制字符， 防止被带到下游日志 /
 *       审计 / CompensationCommand payload 里造成 XSS 或注入。
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleJobRecoveryService implements ConsoleJobRecoveryService {

  private static final String JOB_TYPE_COMPENSATION =
      ConsoleJobOperationsPort.JOB_TYPE_COMPENSATION;
  private static final String ACTION_WORKFLOW_RUN_REPLAY = "WORKFLOW_RUN_REPLAY";

  private final ConsoleJobOperationsPort ops;

  @Override
  public String compensation(CompensationCommandRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    boolean workflowNodeReplay = "WORKFLOW_NODE".equalsIgnoreCase(request.getCompensationType());
    if (!ops.hasText(request.getApprovalId())) {
      if (workflowNodeReplay) {
        boolean workflowNodeReplayRequestIsIncomplete =
            EmptyChecks.isNull(request.getSourceWorkflowRunId())
                || EmptyChecks.isNull(request.getSourceWorkflowNodeRunId())
                || !ops.hasText(request.getOperationKey())
                || !ops.hasText(request.getPreviewToken())
                || EmptyChecks.isNotNull(request.getReplayOperationId());
        if (workflowNodeReplayRequestIsIncomplete) {
          throw BizException.of(
              ResultCode.INVALID_ARGUMENT, "error.workflow.replay_preview_required");
        }
        Long replayOperationId = ops.createPendingWorkflowNodeReplay(request);
        request.setReplayOperationId(replayOperationId);
      }
      String approvalIdempotencyKey =
          workflowNodeReplay ? request.getOperationKey() : idempotencyKey;
      ApprovalSubmitContext approvalCtx = ApprovalSubmitContext.builder()
          .approvalType(JOB_TYPE_COMPENSATION)
          .actionType(JOB_TYPE_COMPENSATION)
          .targetType(
              "WORKFLOW_NODE".equalsIgnoreCase(request.getCompensationType())
                  ? "WORKFLOW_NODE"
                  : "JOB")
          .targetId(String.valueOf(request.getTargetId()))
          .payload(request)
          .approvalReason(request.getReason())
          .idempotencyKey(approvalIdempotencyKey)
          .build();
      String result = ops.submitApproval(approvalCtx);
      ops.publishRefresh(tenantId);
      return result;
    }
    if (workflowNodeReplay && EmptyChecks.isNull(request.getReplayOperationId())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.replay_preview_required");
    }
    ops.requireApprovedApproval(tenantId, request.getApprovalId(), JOB_TYPE_COMPENSATION, request);
    // CPD-OFF: compensation 和 compensate 按设计将不同的请求契约规范化
    // 为相同的领域审计载荷。
    CompensationPayload.CompensationPayloadBuilder compensationPayloadBuilder =
        CompensationPayload.builder()
            .tenantId(tenantId)
            .compensationType(ConsoleTextSanitizer.safeInput(request.getCompensationType(), 64))
            .targetId(request.getTargetId())
            .targetInstanceNo(ConsoleTextSanitizer.safeInput(request.getTargetInstanceNo(), 128))
            .jobCode(ConsoleTextSanitizer.safeInput(request.getJobCode(), 128));
    LocalDate parsedBizDate = ops.parseOptionalBizDate(request.getBizDate());
    CompensationPayload compensationPayload = compensationPayloadBuilder
        .bizDate(parsedBizDate)
        .batchNo(ConsoleTextSanitizer.safeInput(request.getBatchNo(), 128))
        .relatedFileId(request.getRelatedFileId())
        .channelCode(ConsoleTextSanitizer.safeInput(request.getChannelCode(), 128))
        .reason(ConsoleTextSanitizer.safeInput(request.getReason(), 512))
        .operatorId(ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64))
        .approvalId(ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64))
        .strategy(ConsoleTextSanitizer.safeInput(request.getStrategy(), 32))
        .resultPolicy(ConsoleTextSanitizer.safeInput(request.getResultPolicy(), 64))
        .configVersionPolicy(ConsoleTextSanitizer.safeInput(request.getConfigVersionPolicy(), 64))
        .configVersion(request.getConfigVersion())
        .sourceWorkflowRunId(request.getSourceWorkflowRunId())
        .sourceWorkflowNodeRunId(request.getSourceWorkflowNodeRunId())
        .replayOperationId(request.getReplayOperationId())
        .build();
    String result = ops.submitCompensation(compensationPayload, idempotencyKey);
    ops.publishRefresh(tenantId);
    return result;
  }

  @Override
  public ConsoleWorkflowNodeReplayPreviewResponse previewWorkflowNodeReplay(
      WorkflowNodeReplayPreviewRequest request) {
    return ops.previewWorkflowNodeReplay(request);
  }

  @Override
  public ConsoleWorkflowRunReplayPreviewResponse previewWorkflowRunReplay(
      WorkflowRunReplayRequest request) {
    return ops.previewWorkflowRunReplay(request);
  }

  @Override
  public ConsoleWorkflowRunReplaySubmissionResponse submitWorkflowRunReplay(
      WorkflowRunReplayRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    if (!ops.hasText(request.getOperationKey())
        || !request.getOperationKey().equals(idempotencyKey)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.workflow.workflow_run_replay_preview_required");
    }
    Long replayOperationId = ops.createPendingWorkflowRunReplay(request);
    String approvalNo = ops.submitApproval(ApprovalSubmitContext.builder()
        .approvalType(JOB_TYPE_COMPENSATION)
        .actionType(ACTION_WORKFLOW_RUN_REPLAY)
        .targetType("WORKFLOW_RUN")
        .targetId(String.valueOf(request.getWorkflowRunId()))
        .payload(new WorkflowRunReplayApprovalPayload(tenantId, replayOperationId))
        .approvalReason(ConsoleTextSanitizer.safeInput(request.getReason(), 512))
        .idempotencyKey(idempotencyKey)
        .build());
    ops.publishRefresh(tenantId);
    return new ConsoleWorkflowRunReplaySubmissionResponse(replayOperationId, approvalNo);
  }

  // CPD-ON

  @Override
  public String compensate(CompensateRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    boolean workflowNodeReplay = "WORKFLOW_NODE".equalsIgnoreCase(request.getCompensationType());
    if (workflowNodeReplay && !ops.hasText(request.getApprovalId())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.replay_approval_required");
    }
    if (ops.hasText(request.getApprovalId())) {
      ops.requireApprovedApproval(
          tenantId, request.getApprovalId(), JOB_TYPE_COMPENSATION, request);
    }
    String compensationType = EmptyChecks.isNull(request.getCompensationType())
            || EmptyChecks.isBlank(Objects.requireNonNull(request.getCompensationType()))
        ? "JOB"
        : request.getCompensationType();
    CompensationPayload.CompensationPayloadBuilder compensationPayloadBuilder =
        CompensationPayload.builder()
            .tenantId(tenantId)
            .compensationType(compensationType)
            .targetId(request.getTargetId())
            .targetInstanceNo(ConsoleTextSanitizer.safeInput(request.getTargetInstanceNo(), 128))
            .jobCode(ConsoleTextSanitizer.safeInput(request.getJobCode(), 128));
    LocalDate parsedBizDate = ops.parseOptionalBizDate(request.getBizDate());
    CompensationPayload compensationPayload = compensationPayloadBuilder
        .bizDate(parsedBizDate)
        .batchNo(ConsoleTextSanitizer.safeInput(request.getBatchNo(), 128))
        .relatedFileId(request.getRelatedFileId())
        .channelCode(ConsoleTextSanitizer.safeInput(request.getChannelCode(), 128))
        .reason(ConsoleTextSanitizer.safeInput(request.getReason(), 512))
        .operatorId(ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64))
        .approvalId(ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64))
        .strategy(ConsoleTextSanitizer.safeInput(request.getStrategy(), 32))
        .resultPolicy(ConsoleTextSanitizer.safeInput(request.getResultPolicy(), 64))
        .configVersionPolicy(ConsoleTextSanitizer.safeInput(request.getConfigVersionPolicy(), 64))
        .configVersion(request.getConfigVersion())
        .sourceWorkflowRunId(request.getSourceWorkflowRunId())
        .sourceWorkflowNodeRunId(request.getSourceWorkflowNodeRunId())
        .replayOperationId(request.getReplayOperationId())
        .build();
    String result = ops.submitCompensation(compensationPayload, idempotencyKey);
    ops.publishRefresh(tenantId);
    return result;
  }

  @Override
  public String rerun(RerunRequest request, String idempotencyKey) {
    String compensationType = (request.getTargetId() != null
            || (request.getTargetInstanceNo() != null
                && !request.getTargetInstanceNo().isBlank()))
        ? "JOB"
        : "BATCH";
    String tenantId = ops.resolveTenant(request.getTenantId());
    validateRerunPolicy(request);
    if (ops.hasText(request.getApprovalId())) {
      ops.requireApprovedApproval(tenantId, request.getApprovalId(), "RERUN", request);
    }
    CompensationPayload.CompensationPayloadBuilder compensationPayloadBuilder =
        CompensationPayload.builder()
            .tenantId(tenantId)
            .compensationType(compensationType)
            .targetId(request.getTargetId())
            .targetInstanceNo(ConsoleTextSanitizer.safeInput(request.getTargetInstanceNo(), 128))
            .jobCode(ConsoleTextSanitizer.safeInput(request.getJobCode(), 128));
    LocalDate parsedBizDate = ops.parseOptionalBizDate(request.getBizDate());
    CompensationPayload compensationPayload = compensationPayloadBuilder
        .bizDate(parsedBizDate)
        .batchNo(ConsoleTextSanitizer.safeInput(request.getBatchNo(), 128))
        .relatedFileId(request.getRelatedFileId())
        .reason(ConsoleTextSanitizer.safeInput(request.getReason(), 512))
        .operatorId(ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64))
        .approvalId(ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64))
        .strategy(ConsoleTextSanitizer.safeInput(request.getStrategy(), 32))
        .resultPolicy(ConsoleTextSanitizer.safeInput(request.getResultPolicy(), 64))
        .configVersionPolicy(ConsoleTextSanitizer.safeInput(request.getConfigVersionPolicy(), 64))
        .configVersion(request.getConfigVersion())
        .build();
    String result = ops.submitCompensation(compensationPayload, idempotencyKey);
    ops.publishRefresh(tenantId);
    return result;
  }

  /**
   * 跨字段校验：USE_SPECIFIED_VERSION 必须配 configVersion；其余 policy 不带 version。 单字段合法值由
   * RerunRequest.@Pattern / @Positive 守住。
   */
  private void validateRerunPolicy(RerunRequest request) {
    if (ConfigVersionPolicy.USE_SPECIFIED_VERSION.code().equals(request.getConfigVersionPolicy())
        && request.getConfigVersion() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.rerun.config_version_required");
    }
  }

  @Override
  public String replayDeadLetter(DeadLetterReplayRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    if (!ops.hasText(request.getApprovalId())) {
      ApprovalSubmitContext approvalCtx = ApprovalSubmitContext.builder()
          .approvalType("DLQ_REPLAY")
          .actionType("DLQ_REPLAY")
          .targetType("DLQ")
          .targetId(String.valueOf(request.getDeadLetterId()))
          .payload(request)
          .approvalReason(request.getReason())
          .idempotencyKey(idempotencyKey)
          .build();
      String result = ops.submitApproval(approvalCtx);
      ops.publishRefresh(tenantId);
      return result;
    }
    ops.requireApprovedApproval(tenantId, request.getApprovalId(), "DLQ_REPLAY", request);
    String result = ops.submitCompensation(
        CompensationPayload.builder()
            .tenantId(tenantId)
            .compensationType("DLQ")
            .targetId(request.getDeadLetterId())
            .reason(ConsoleTextSanitizer.safeInput(request.getReason(), 512))
            .operatorId(ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64))
            .approvalId(ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64))
            .strategy(ConsoleTextSanitizer.safeInput(request.getStrategy(), 32))
            .externalActionVerifiedNotExecuted(request.getExternalActionVerifiedNotExecuted())
            .externalOutcomeEvidenceReference(
                ConsoleTextSanitizer.safeInput(request.getExternalOutcomeEvidenceReference(), 512))
            .build(),
        idempotencyKey);
    ops.publishRefresh(tenantId);
    return result;
  }

  @Override
  public String replayTask(TaskReplayRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    if (!ops.hasText(request.getApprovalId())) {
      ApprovalSubmitContext approvalCtx = ApprovalSubmitContext.builder()
          .approvalType(JOB_TYPE_COMPENSATION)
          .actionType("RETRY")
          .targetType("JOB_TASK")
          .targetId(String.valueOf(request.getTaskId()))
          .payload(request)
          .approvalReason(request.getReason())
          .idempotencyKey(idempotencyKey)
          .build();
      String result = ops.submitApproval(approvalCtx);
      ops.publishRefresh(tenantId);
      return result;
    }
    ops.requireApprovedApproval(tenantId, request.getApprovalId(), "RETRY", request);
    String result = ops.triggerRecovery(
        tenantId,
        "/internal/recoveries/tasks/{taskId}/replay",
        request.getTaskId(),
        idempotencyKey);
    ops.publishRefresh(tenantId);
    return result;
  }

  @Override
  public String replayPartition(PartitionReplayRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    if (!ops.hasText(request.getApprovalId())) {
      ApprovalSubmitContext approvalCtx = ApprovalSubmitContext.builder()
          .approvalType(JOB_TYPE_COMPENSATION)
          .actionType("RETRY")
          .targetType("JOB_PARTITION")
          .targetId(String.valueOf(request.getPartitionId()))
          .payload(request)
          .approvalReason(request.getReason())
          .idempotencyKey(idempotencyKey)
          .build();
      String result = ops.submitApproval(approvalCtx);
      ops.publishRefresh(tenantId);
      return result;
    }
    ops.requireApprovedApproval(tenantId, request.getApprovalId(), "RETRY", request);
    String result = ops.triggerRecovery(
        tenantId,
        "/internal/recoveries/partitions/{partitionId}/replay",
        request.getPartitionId(),
        idempotencyKey);
    ops.publishRefresh(tenantId);
    return result;
  }

  private record WorkflowRunReplayApprovalPayload(String tenantId, Long replayOperationId) {}
}
