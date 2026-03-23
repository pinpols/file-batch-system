package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.ApprovalCommandStatus;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.orchestrator.domain.entity.ApprovalCommandEntity;
import com.example.batch.orchestrator.mapper.ApprovalCommandMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DefaultApprovalWorkflowService implements ApprovalWorkflowService {

    private final ApprovalCommandMapper approvalCommandMapper;

    @Override
    @Transactional
    public String submit(String tenantId,
                         String approvalType,
                         String actionType,
                         String targetType,
                         String targetId,
                         String payloadJson,
                         String requesterId,
                         String sourceTraceId,
                         String sourceIdempotencyKey,
                         String approvalReason) {
        String approvalNo = IdGenerator.newBusinessNo("apr");
        ApprovalCommandEntity entity = new ApprovalCommandEntity();
        entity.setTenantId(tenantId);
        entity.setApprovalNo(approvalNo);
        entity.setApprovalType(approvalType);
        entity.setActionType(actionType);
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        entity.setPayloadJson(StringUtils.hasText(payloadJson) ? payloadJson : "{}");
        entity.setApprovalStatus(ApprovalCommandStatus.PENDING.code());
        entity.setRequesterId(requesterId);
        entity.setSourceTraceId(sourceTraceId);
        entity.setSourceIdempotencyKey(sourceIdempotencyKey);
        entity.setApprovalReason(approvalReason);
        approvalCommandMapper.insert(entity);
        return approvalNo;
    }

    @Override
    @Transactional
    public ApprovalRecord approve(String tenantId, String approvalNo, String approverId, String approvalReason) {
        ApprovalCommandEntity entity = require(tenantId, approvalNo);
        if (!ApprovalCommandStatus.PENDING.code().equals(entity.getApprovalStatus())) {
            return toRecord(entity);
        }
        if (approvalCommandMapper.markApproved(tenantId, approvalNo, approverId, approvalReason,
                ApprovalCommandStatus.APPROVED.code(), ApprovalCommandStatus.PENDING.code()) <= 0) {
            return toRecord(require(tenantId, approvalNo));
        }
        return toRecord(require(tenantId, approvalNo));
    }

    @Override
    @Transactional
    public ApprovalRecord reject(String tenantId, String approvalNo, String approverId, String approvalReason) {
        ApprovalCommandEntity entity = require(tenantId, approvalNo);
        if (!ApprovalCommandStatus.PENDING.code().equals(entity.getApprovalStatus())) {
            return toRecord(entity);
        }
        if (approvalCommandMapper.markRejected(tenantId, approvalNo, approverId, approvalReason,
                ApprovalCommandStatus.REJECTED.code(), ApprovalCommandStatus.PENDING.code()) <= 0) {
            return toRecord(require(tenantId, approvalNo));
        }
        return toRecord(require(tenantId, approvalNo));
    }

    @Override
    @Transactional
    public ApprovalRecord markExecuted(String tenantId, String approvalNo) {
        if (approvalCommandMapper.markExecuted(tenantId, approvalNo,
                ApprovalCommandStatus.EXECUTED.code(), ApprovalCommandStatus.APPROVED.code()) <= 0) {
            return toRecord(require(tenantId, approvalNo));
        }
        return toRecord(require(tenantId, approvalNo));
    }

    @Override
    public ApprovalRecord get(String tenantId, String approvalNo) {
        return toRecord(require(tenantId, approvalNo));
    }

    private ApprovalCommandEntity require(String tenantId, String approvalNo) {
        ApprovalCommandEntity entity = approvalCommandMapper.selectByTenantAndApprovalNo(tenantId, approvalNo);
        if (entity == null) {
            throw new IllegalStateException("approval request not found");
        }
        return entity;
    }

    private ApprovalRecord toRecord(ApprovalCommandEntity entity) {
        return new ApprovalRecord(
                entity.getTenantId(),
                entity.getApprovalNo(),
                entity.getApprovalType(),
                entity.getActionType(),
                entity.getTargetType(),
                entity.getTargetId(),
                entity.getPayloadJson(),
                entity.getApprovalStatus(),
                entity.getRequesterId(),
                entity.getApproverId(),
                entity.getRejectionReason(),
                entity.getApprovalReason(),
                entity.getSourceTraceId(),
                entity.getSourceIdempotencyKey()
        );
    }
}
