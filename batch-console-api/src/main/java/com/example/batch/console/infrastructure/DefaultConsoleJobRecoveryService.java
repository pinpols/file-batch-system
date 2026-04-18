package com.example.batch.console.infrastructure;

import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.console.application.ConsoleJobRecoveryService;
import com.example.batch.console.infrastructure.ConsoleJobOpsSupport.ApprovalSubmitContext;
import com.example.batch.console.infrastructure.ConsoleJobOpsSupport.CompensationPayload;
import com.example.batch.console.web.request.CompensateRequest;
import com.example.batch.console.web.request.CompensationCommandRequest;
import com.example.batch.console.web.request.DeadLetterReplayRequest;
import com.example.batch.console.web.request.PartitionReplayRequest;
import com.example.batch.console.web.request.RerunRequest;
import com.example.batch.console.web.request.TaskReplayRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 控制台作业恢复入口：补偿（compensation/compensate）、重跑（rerun）、死信重放、分区重放、任务重放 5 类。
 *
 * <p>关键模式：
 *
 * <ul>
 *   <li><b>审批两阶段</b>（compensation / replayDeadLetter / replayTask / replayPartition）：请求未带
 *       {@code approvalId} 时先发起审批 → 返回 approvalNo，前端把它带回来再调用本接口；带 {@code approvalId}
 *       时先 {@code requireApprovedApproval} 校验审批通过态才真正提交。这样保证敏感恢复操作必须经过审批流
 *       而不能直连触发（{@code compensate} / {@code rerun} 除外——它们假定调用方已自行鉴权）。
 *   <li><b>事件广播</b>：每次成功操作都 {@code publishRefresh(tenantId)} 让前端视图立即刷新，避免用户重复提交。
 *   <li><b>文本清洗</b>：所有自由文本入参（reason / operatorId / approvalId / strategy 等）统一经
 *       {@link com.example.batch.common.utils.ConsoleTextSanitizer#safeInput} 截断并过滤控制字符，
 *       防止被带到下游日志 / 审计 / CompensationCommand payload 里造成 XSS 或注入。
 * </ul>
 */
@Service
@RequiredArgsConstructor
class DefaultConsoleJobRecoveryService implements ConsoleJobRecoveryService {

  private static final String JOB_TYPE_COMPENSATION = ConsoleJobOpsSupport.jobTypeCompensation();

  private final ConsoleJobOpsSupport ops;

  @Override
  public String compensation(CompensationCommandRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    if (!ops.hasText(request.getApprovalId())) {
      String result =
          ops.submitApproval(
              new ApprovalSubmitContext(
                  JOB_TYPE_COMPENSATION,
                  JOB_TYPE_COMPENSATION,
                  "JOB",
                  String.valueOf(request.getTargetId()),
                  request,
                  request.getReason(),
                  idempotencyKey));
      ops.publishRefresh(tenantId);
      return result;
    }
    ops.requireApprovedApproval(tenantId, request.getApprovalId());
    String result =
        ops.submitCompensation(
            CompensationPayload.builder()
                .tenantId(tenantId)
                .compensationType(
                    ConsoleTextSanitizer.safeInput(request.getCompensationType(), 64))
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
                .build(),
            idempotencyKey);
    ops.publishRefresh(tenantId);
    return result;
  }

  @Override
  public String replayDeadLetter(DeadLetterReplayRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    if (!ops.hasText(request.getApprovalId())) {
      String result =
          ops.submitApproval(
              new ApprovalSubmitContext(
                  "DLQ_REPLAY",
                  "DLQ_REPLAY",
                  "DLQ",
                  String.valueOf(request.getDeadLetterId()),
                  request,
                  request.getReason(),
                  idempotencyKey));
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
      String result =
          ops.submitApproval(
              new ApprovalSubmitContext(
                  JOB_TYPE_COMPENSATION,
                  "RETRY",
                  "JOB_TASK",
                  String.valueOf(request.getTaskId()),
                  request,
                  request.getReason(),
                  idempotencyKey));
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
      String result =
          ops.submitApproval(
              new ApprovalSubmitContext(
                  JOB_TYPE_COMPENSATION,
                  "RETRY",
                  "JOB_PARTITION",
                  String.valueOf(request.getPartitionId()),
                  request,
                  request.getReason(),
                  idempotencyKey));
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
