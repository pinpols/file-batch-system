package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.ApprovalCommandStatus;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.domain.entity.ApprovalCommandEntity;
import com.example.batch.orchestrator.mapper.ApprovalCommandMapper;
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
    String approvalNo = IdGenerator.newBusinessNo("apr");
    ApprovalCommandEntity entity = new ApprovalCommandEntity();
    entity.setTenantId(command.tenantId());
    entity.setApprovalNo(approvalNo);
    entity.setApprovalType(command.approvalType());
    entity.setActionType(command.actionType());
    entity.setTargetType(command.targetType());
    entity.setTargetId(command.targetId());
    entity.setPayloadJson(Texts.hasText(command.payloadJson()) ? command.payloadJson() : "{}");
    entity.setApprovalStatus(ApprovalCommandStatus.PENDING.code());
    entity.setRequesterId(command.requesterId());
    entity.setSourceTraceId(command.sourceTraceId());
    entity.setSourceIdempotencyKey(command.sourceIdempotencyKey());
    entity.setApprovalReason(command.approvalReason());
    approvalCommandMapper.insert(entity);
    return approvalNo;
  }

  @Override
  @Transactional
  public ApprovalRecord approve(
      String tenantId, String approvalNo, String approverId, String approvalReason) {
    ApprovalCommandEntity entity = require(tenantId, approvalNo);
    if (!ApprovalCommandStatus.PENDING.code().equals(entity.getApprovalStatus())) {
      return toRecord(entity);
    }
    if (approvalCommandMapper.markApproved(
            tenantId,
            approvalNo,
            approverId,
            approvalReason,
            ApprovalCommandStatus.APPROVED.code(),
            ApprovalCommandStatus.PENDING.code())
        <= 0) {
      return toRecord(require(tenantId, approvalNo));
    }
    return toRecord(require(tenantId, approvalNo));
  }

  @Override
  @Transactional
  public ApprovalRecord reject(
      String tenantId, String approvalNo, String approverId, String approvalReason) {
    ApprovalCommandEntity entity = require(tenantId, approvalNo);
    if (!ApprovalCommandStatus.PENDING.code().equals(entity.getApprovalStatus())) {
      return toRecord(entity);
    }
    if (approvalCommandMapper.markRejected(
            tenantId,
            approvalNo,
            approverId,
            approvalReason,
            ApprovalCommandStatus.REJECTED.code(),
            ApprovalCommandStatus.PENDING.code())
        <= 0) {
      return toRecord(require(tenantId, approvalNo));
    }
    return toRecord(require(tenantId, approvalNo));
  }

  /**
   * 把已审批命令推进到 EXECUTED（APPROVED → EXECUTED CAS）。CAS 失败时必须区分两种语义：
   *
   * <ul>
   *   <li>当前已是 EXECUTED：幂等重复调用（例如执行回调重试），直接返回当前记录。
   *   <li>当前非 APPROVED 且非 EXECUTED：非法状态转换（如 PENDING/REJECTED），抛 {@link IllegalStateException}
   *       防止把未审批或已拒绝的命令偷偷执行掉。
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
        throw new IllegalStateException(
            "markExecuted failed: approvalNo="
                + approvalNo
                + " is not in APPROVED state, current status="
                + current.getApprovalStatus());
      }
      return toRecord(current);
    }
    return toRecord(require(tenantId, approvalNo));
  }

  @Override
  public ApprovalRecord get(String tenantId, String approvalNo) {
    return toRecord(require(tenantId, approvalNo));
  }

  private ApprovalCommandEntity require(String tenantId, String approvalNo) {
    ApprovalCommandEntity entity =
        approvalCommandMapper.selectByTenantAndApprovalNo(tenantId, approvalNo);
    if (entity == null) {
      throw new IllegalStateException("approval request not found");
    }
    return entity;
  }

  private ApprovalRecord toRecord(ApprovalCommandEntity entity) {
    return ApprovalRecord.of(
        new ApprovalIdentity(new ApprovalContext(entity.getTenantId()), entity.getApprovalNo()),
        new ApprovalTarget(
            entity.getApprovalType(),
            entity.getActionType(),
            entity.getTargetType(),
            entity.getTargetId(),
            entity.getPayloadJson()),
        new ApprovalOutcome(
            entity.getApprovalStatus(),
            entity.getApproverId(),
            entity.getRejectionReason(),
            entity.getApprovalReason()),
        new ApprovalSource(
            entity.getRequesterId(),
            entity.getSourceTraceId(),
            entity.getSourceIdempotencyKey(),
            entity.getApprovalReason()));
  }
}
