package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.common.enums.ApprovalCommandStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.ApprovalCommandEntity;
import io.github.pinpols.batch.orchestrator.domain.param.ApproveCommandDecisionParam;
import io.github.pinpols.batch.orchestrator.domain.param.RejectCommandDecisionParam;
import io.github.pinpols.batch.orchestrator.mapper.ApprovalCommandMapper;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审批命令状态机：{@code PENDING → APPROVED / REJECTED → EXECUTED}。
 *
 * <p>所有转换以 {@code (fromStatus, toStatus)} 做 DB 级 CAS，并发重复审批天然互斥： CAS
 * 成功者推进状态，失败者重读取当前状态返回（幂等）。{@link #markExecuted} 多一层区分—— CAS 失败时"已是 EXECUTED"视为幂等重复，"当前非
 * APPROVED"则是非法状态转换要抛 {@link IllegalStateException}。
 */
@Service
@RequiredArgsConstructor
public class DefaultApprovalWorkflowService implements ApprovalWorkflowService {

  private final ApprovalCommandMapper approvalCommandMapper;

  @Override
  @Transactional
  public String submit(ApprovalSubmitCommand command) {
    if (Texts.hasText(command.sourceIdempotencyKey())) {
      ApprovalCommandEntity existing = findBySourceIdempotencyKey(command);
      if (EmptyChecks.isNotNull(existing)) {
        ensureSameSubmission(command, existing);
        return existing.getApprovalNo();
      }
    }
    String approvalNo = IdGenerator.newBusinessNo("apr");
    ApprovalCommandEntity entity = new ApprovalCommandEntity();
    entity.setTenantId(command.tenantId());
    entity.setApprovalNo(approvalNo);
    entity.setApprovalType(command.approvalType());
    entity.setActionType(command.actionType());
    entity.setTargetType(command.targetType());
    entity.setTargetId(command.targetId());
    String payloadJson = command.payloadJson();
    String resolvedPayloadJson = Texts.hasText(payloadJson) ? payloadJson : "{}";
    entity.setPayloadJson(resolvedPayloadJson);
    entity.setApprovalStatus(ApprovalCommandStatus.PENDING.code());
    entity.setRequesterId(command.requesterId());
    entity.setSourceTraceId(command.sourceTraceId());
    entity.setSourceIdempotencyKey(
        Texts.hasText(command.sourceIdempotencyKey()) ? command.sourceIdempotencyKey() : null);
    entity.setApprovalReason(command.approvalReason());
    entity.setSourceApprovalReason(command.approvalReason());
    int inserted = approvalCommandMapper.insert(entity);
    if (inserted == 0 && Texts.hasText(command.sourceIdempotencyKey())) {
      ApprovalCommandEntity existing = findBySourceIdempotencyKey(command);
      if (EmptyChecks.isNotNull(existing)) {
        ensureSameSubmission(command, existing);
        return existing.getApprovalNo();
      }
    }
    if (inserted == 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.approval.submit_failed");
    }
    return approvalNo;
  }

  private ApprovalCommandEntity findBySourceIdempotencyKey(ApprovalSubmitCommand command) {
    return approvalCommandMapper.selectByTenantAndSourceIdempotencyKey(
        command.tenantId(), command.sourceIdempotencyKey());
  }

  private void ensureSameSubmission(ApprovalSubmitCommand command, ApprovalCommandEntity existing) {
    String payloadJson = Texts.hasText(command.payloadJson()) ? command.payloadJson() : "{}";
    Object submittedPayload = JsonUtils.fromJson(payloadJson, Object.class);
    Object existingPayload = JsonUtils.fromJson(existing.getPayloadJson(), Object.class);
    boolean samePayload = submittedPayload.equals(existingPayload);
    boolean sameCommand = command.approvalType().equals(existing.getApprovalType())
        && command.actionType().equals(existing.getActionType())
        && command.targetType().equals(existing.getTargetType())
        && Objects.equals(command.targetId(), existing.getTargetId())
        && Objects.equals(command.requesterId(), existing.getRequesterId())
        && Objects.equals(command.approvalReason(), existing.getSourceApprovalReason())
        && samePayload;
    if (!sameCommand) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.approval.idempotency_conflict");
    }
  }

  @Override
  @Transactional
  public ApprovalRecord approve(
      String tenantId, String approvalNo, String approverId, String approvalReason) {
    ApprovalCommandEntity entity = require(tenantId, approvalNo);
    if (!ApprovalCommandStatus.PENDING.code().equals(entity.getApprovalStatus())) {
      return toRecord(entity);
    }
    ApproveCommandDecisionParam approveCommandDecisionParam = ApproveCommandDecisionParam.builder()
        .tenantId(tenantId)
        .approvalNo(approvalNo)
        .approverId(approverId)
        .approvalReason(approvalReason)
        .approvedStatus(ApprovalCommandStatus.APPROVED.code())
        .pendingStatus(ApprovalCommandStatus.PENDING.code())
        .build();
    if (approvalCommandMapper.markApproved(approveCommandDecisionParam) <= 0) {
      ApprovalCommandEntity refreshedApproval = require(tenantId, approvalNo);
      return toRecord(refreshedApproval);
    }
    ApprovalCommandEntity persistedApproval = require(tenantId, approvalNo);
    return toRecord(persistedApproval);
  }

  @Override
  @Transactional
  public ApprovalRecord reject(
      String tenantId, String approvalNo, String approverId, String approvalReason) {
    ApprovalCommandEntity entity = require(tenantId, approvalNo);
    if (!ApprovalCommandStatus.PENDING.code().equals(entity.getApprovalStatus())) {
      return toRecord(entity);
    }
    RejectCommandDecisionParam rejectCommandDecisionParam = RejectCommandDecisionParam.builder()
        .tenantId(tenantId)
        .approvalNo(approvalNo)
        .approverId(approverId)
        .approvalReason(approvalReason)
        .rejectedStatus(ApprovalCommandStatus.REJECTED.code())
        .pendingStatus(ApprovalCommandStatus.PENDING.code())
        .build();
    if (approvalCommandMapper.markRejected(rejectCommandDecisionParam) <= 0) {
      ApprovalCommandEntity refreshedApproval = require(tenantId, approvalNo);
      return toRecord(refreshedApproval);
    }
    ApprovalCommandEntity persistedApproval = require(tenantId, approvalNo);
    return toRecord(persistedApproval);
  }

  /**
   * 把已审批命令推进到 EXECUTED（APPROVED → EXECUTED CAS）。CAS 失败时必须区分两种语义：
   *
   * <ul>
   *   <li>当前已是 EXECUTED：幂等重复调用（例如执行回调重试），直接返回当前记录。
   *   <li>当前非 APPROVED 且非 EXECUTED：非法状态转换（如 PENDING/REJECTED），抛 {@link BizException}
   *       （STATE_CONFLICT → 409）防止把未审批或已拒绝的命令偷偷执行掉。
   * </ul>
   */
  @Override
  @Transactional
  public ApprovalRecord markExecuted(String tenantId, String approvalNo) {
    if (approvalCommandMapper.markExecuted(
            tenantId,
            approvalNo,
            ApprovalCommandStatus.EXECUTED.code(),
            ApprovalCommandStatus.APPROVED.code())
        <= 0) {
      // M-3: 区分幂等重复执行（已是 EXECUTED）与非法状态转换
      ApprovalCommandEntity current = require(tenantId, approvalNo);
      if (!ApprovalCommandStatus.EXECUTED.code().equals(current.getApprovalStatus())) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT,
            "error.approval.not_in_approved_state",
            approvalNo,
            current.getApprovalStatus());
      }
      return toRecord(current);
    }
    ApprovalCommandEntity persistedApproval = require(tenantId, approvalNo);
    return toRecord(persistedApproval);
  }

  @Override
  public ApprovalRecord get(String tenantId, String approvalNo) {
    ApprovalCommandEntity approval = require(tenantId, approvalNo);
    return toRecord(approval);
  }

  @Override
  @Transactional(readOnly = true)
  public ApprovalRecord getBySourceKey(String tenantId, String sourceIdempotencyKey) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(sourceIdempotencyKey)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
    ApprovalCommandEntity entity =
        approvalCommandMapper.selectByTenantAndSourceIdempotencyKey(tenantId, sourceIdempotencyKey);
    if (EmptyChecks.isNull(entity)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.approval.not_found");
    }
    return toRecord(entity);
  }

  private ApprovalCommandEntity require(String tenantId, String approvalNo) {
    ApprovalCommandEntity entity =
        approvalCommandMapper.selectByTenantAndApprovalNo(tenantId, approvalNo);
    if (entity == null) {
      // 审批单不存在 → 404 NOT_FOUND（之前抛 IllegalStateException 被回退 handler 当 500 系统错误,
      // console 代理如实透传 500 误导调用方）。approve / reject / get 共用本方法,一处修三处生效。
      throw BizException.of(ResultCode.NOT_FOUND, "error.approval.not_found");
    }
    return entity;
  }

  private ApprovalRecord toRecord(ApprovalCommandEntity entity) {
    ApprovalContext context = new ApprovalContext(entity.getTenantId());
    ApprovalIdentity identity = new ApprovalIdentity(context, entity.getApprovalNo());
    ApprovalTarget target = new ApprovalTarget(
        entity.getApprovalType(),
        entity.getActionType(),
        entity.getTargetType(),
        entity.getTargetId(),
        entity.getPayloadJson());
    ApprovalOutcome outcome = new ApprovalOutcome(
        entity.getApprovalStatus(),
        entity.getApproverId(),
        entity.getRejectionReason(),
        entity.getApprovalReason());
    ApprovalSource source = new ApprovalSource(
        entity.getRequesterId(),
        entity.getSourceTraceId(),
        entity.getSourceIdempotencyKey(),
        entity.getSourceApprovalReason());
    return ApprovalRecord.of(identity, target, outcome, source);
  }
}
