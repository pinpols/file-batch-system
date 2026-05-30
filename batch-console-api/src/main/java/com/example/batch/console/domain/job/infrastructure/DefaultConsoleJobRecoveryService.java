package com.example.batch.console.domain.job.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
<<<<<<< HEAD:batch-console-api/src/main/java/com/example/batch/console/infrastructure/job/DefaultConsoleJobRecoveryService.java
import com.example.batch.console.application.job.ConsoleJobRecoveryService;
import com.example.batch.console.domain.governance.web.request.DeadLetterReplayRequest;
import com.example.batch.console.infrastructure.query.ConsoleJobOpsSupport;
import com.example.batch.console.infrastructure.query.ConsoleJobOpsSupport.ApprovalSubmitContext;
import com.example.batch.console.infrastructure.query.ConsoleJobOpsSupport.CompensationPayload;
import com.example.batch.console.web.request.job.CompensateRequest;
import com.example.batch.console.web.request.job.CompensationCommandRequest;
import com.example.batch.console.web.request.job.PartitionReplayRequest;
import com.example.batch.console.web.request.job.RerunRequest;
import com.example.batch.console.web.request.job.TaskReplayRequest;
import com.example.batch.console.domain.job.application.ConsoleJobRecoveryService;
import com.example.batch.console.domain.job.web.request.CompensateRequest;
import com.example.batch.console.domain.job.web.request.CompensationCommandRequest;
import com.example.batch.console.domain.job.web.request.PartitionReplayRequest;
import com.example.batch.console.domain.job.web.request.RerunRequest;
import com.example.batch.console.domain.job.web.request.TaskReplayRequest;
import com.example.batch.console.infrastructure.query.ConsoleJobOpsSupport;
import com.example.batch.console.infrastructure.query.ConsoleJobOpsSupport.ApprovalSubmitContext;
import com.example.batch.console.infrastructure.query.ConsoleJobOpsSupport.CompensationPayload;
import com.example.batch.console.web.request.ops.DeadLetterReplayRequest;
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
 *       com.example.batch.common.utils.ConsoleTextSanitizer#safeInput} 截断并过滤控制字符， 防止被带到下游日志 / 审计 /
 *       CompensationCommand payload 里造成 XSS 或注入。
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleJobRecoveryService implements ConsoleJobRecoveryService {

  private static final String JOB_TYPE_COMPENSATION = ConsoleJobOpsSupport.jobTypeCompensation();

  private final ConsoleJobOpsSupport ops;

  @Override
  public String compensation(CompensationCommandRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    if (!ops.hasText(request.getApprovalId())) {
      ApprovalSubmitContext approvalCtx =
          ApprovalSubmitContext.builder()
              .approvalType(JOB_TYPE_COMPENSATION)
              .actionType(JOB_TYPE_COMPENSATION)
              .targetType("JOB")
              .targetId(String.valueOf(request.getTargetId()))
              .payload(request)
              .approvalReason(request.getReason())
              .idempotencyKey(idempotencyKey)
              .build();
      String result = ops.submitApproval(approvalCtx);
      ops.publishRefresh(tenantId);
      return result;
    }
    ops.requireApprovedApproval(tenantId, request.getApprovalId());
    String result =
        ops.submitCompensation(
            CompensationPayload.builder()
                .tenantId(tenantId)
                .compensationType(ConsoleTextSanitizer.safeInput(request.getCompensationType(), 64))
                .targetId(request.getTargetId())
                .targetInstanceNo(
                    ConsoleTextSanitizer.safeInput(request.getTargetInstanceNo(), 128))
                .jobCode(ConsoleTextSanitizer.safeInput(request.getJobCode(), 128))
                .bizDate(ops.parseOptionalBizDate(request.getBizDate()))
                .batchNo(ConsoleTextSanitizer.safeInput(request.getBatchNo(), 128))
                .relatedFileId(request.getRelatedFileId())
                .channelCode(ConsoleTextSanitizer.safeInput(request.getChannelCode(), 128))
                .reason(ConsoleTextSanitizer.safeInput(request.getReason(), 512))
                .operatorId(ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64))
                .approvalId(ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64))
                .strategy(ConsoleTextSanitizer.safeInput(request.getStrategy(), 32))
                .build(),
            idempotencyKey);
    ops.publishRefresh(tenantId);
    return result;
  }

  @Override
  public String compensate(CompensateRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    String result =
        ops.submitCompensation(
            CompensationPayload.builder()
                .tenantId(tenantId)
                .compensationType(
                    request.getCompensationType() == null || request.getCompensationType().isBlank()
                        ? "JOB"
                        : request.getCompensationType())
                .targetId(request.getTargetId())
                .targetInstanceNo(
                    ConsoleTextSanitizer.safeInput(request.getTargetInstanceNo(), 128))
                .jobCode(ConsoleTextSanitizer.safeInput(request.getJobCode(), 128))
                .bizDate(ops.parseOptionalBizDate(request.getBizDate()))
                .batchNo(ConsoleTextSanitizer.safeInput(request.getBatchNo(), 128))
                .relatedFileId(request.getRelatedFileId())
                .channelCode(ConsoleTextSanitizer.safeInput(request.getChannelCode(), 128))
                .reason(ConsoleTextSanitizer.safeInput(request.getReason(), 512))
                .operatorId(ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64))
                .approvalId(ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64))
                .strategy(ConsoleTextSanitizer.safeInput(request.getStrategy(), 32))
                .build(),
            idempotencyKey);
    ops.publishRefresh(tenantId);
    return result;
  }

  @Override
  public String rerun(RerunRequest request, String idempotencyKey) {
    String compensationType =
        (request.getTargetId() != null
                || (request.getTargetInstanceNo() != null
                    && !request.getTargetInstanceNo().isBlank()))
            ? "JOB"
            : "BATCH";
    String tenantId = ops.resolveTenant(request.getTenantId());
    validateRerunPolicy(request);
    String result =
        ops.submitCompensation(
            CompensationPayload.builder()
                .tenantId(tenantId)
                .compensationType(compensationType)
                .targetId(request.getTargetId())
                .targetInstanceNo(
                    ConsoleTextSanitizer.safeInput(request.getTargetInstanceNo(), 128))
                .jobCode(ConsoleTextSanitizer.safeInput(request.getJobCode(), 128))
                .bizDate(ops.parseOptionalBizDate(request.getBizDate()))
                .batchNo(ConsoleTextSanitizer.safeInput(request.getBatchNo(), 128))
                .relatedFileId(request.getRelatedFileId())
                .reason(ConsoleTextSanitizer.safeInput(request.getReason(), 512))
                .operatorId(ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64))
                .approvalId(ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64))
                .strategy(ConsoleTextSanitizer.safeInput(request.getStrategy(), 32))
                .resultPolicy(ConsoleTextSanitizer.safeInput(request.getResultPolicy(), 64))
                .configVersionPolicy(
                    ConsoleTextSanitizer.safeInput(request.getConfigVersionPolicy(), 64))
                .configVersion(request.getConfigVersion())
                .build(),
            idempotencyKey);
    ops.publishRefresh(tenantId);
    return result;
  }

  /**
   * 跨字段校验：USE_SPECIFIED_VERSION 必须配 configVersion；其余 policy 不带 version。 单字段合法值由
   * RerunRequest.@Pattern / @Positive 守住。
   */
  private void validateRerunPolicy(RerunRequest request) {
    if ("USE_SPECIFIED_VERSION".equals(request.getConfigVersionPolicy())
        && request.getConfigVersion() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.rerun.config_version_required");
    }
  }

  @Override
  public String replayDeadLetter(DeadLetterReplayRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    if (!ops.hasText(request.getApprovalId())) {
      ApprovalSubmitContext approvalCtx =
          ApprovalSubmitContext.builder()
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
    ops.requireApprovedApproval(tenantId, request.getApprovalId());
    String result =
        ops.submitCompensation(
            CompensationPayload.builder()
                .tenantId(tenantId)
                .compensationType("DLQ")
                .targetId(request.getDeadLetterId())
                .reason(ConsoleTextSanitizer.safeInput(request.getReason(), 512))
                .operatorId(ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64))
                .approvalId(ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64))
                .strategy(ConsoleTextSanitizer.safeInput(request.getStrategy(), 32))
                .build(),
            idempotencyKey);
    ops.publishRefresh(tenantId);
    return result;
  }

  @Override
  public String replayTask(TaskReplayRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    if (!ops.hasText(request.getApprovalId())) {
      ApprovalSubmitContext approvalCtx =
          ApprovalSubmitContext.builder()
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
    ops.requireApprovedApproval(tenantId, request.getApprovalId());
    String result =
        ops.triggerRecovery(
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
      ApprovalSubmitContext approvalCtx =
          ApprovalSubmitContext.builder()
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
    ops.requireApprovedApproval(tenantId, request.getApprovalId());
    String result =
        ops.triggerRecovery(
            tenantId,
            "/internal/recoveries/partitions/{partitionId}/replay",
            request.getPartitionId(),
            idempotencyKey);
    ops.publishRefresh(tenantId);
    return result;
  }
}
