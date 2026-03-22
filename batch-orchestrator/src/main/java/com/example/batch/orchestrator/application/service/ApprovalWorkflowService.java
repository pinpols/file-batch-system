package com.example.batch.orchestrator.application.service;

public interface ApprovalWorkflowService {

    String submit(String tenantId,
                  String approvalType,
                  String actionType,
                  String targetType,
                  String targetId,
                  String payloadJson,
                  String requesterId,
                  String sourceTraceId,
                  String sourceIdempotencyKey,
                  String approvalReason);

    ApprovalRecord approve(String tenantId, String approvalNo, String approverId, String approvalReason);

    ApprovalRecord reject(String tenantId, String approvalNo, String approverId, String approvalReason);

    ApprovalRecord markExecuted(String tenantId, String approvalNo);

    ApprovalRecord get(String tenantId, String approvalNo);

    record ApprovalRecord(
            String tenantId,
            String approvalNo,
            String approvalType,
            String actionType,
            String targetType,
            String targetId,
            String payloadJson,
            String approvalStatus,
            String requesterId,
            String approverId,
            String rejectionReason,
            String approvalReason,
            String sourceTraceId,
            String sourceIdempotencyKey
    ) {
    }
}
