package com.example.batch.console.application;

public interface ConsoleApprovalApplicationService {

    String approve(String tenantId, String approvalNo, String operatorId, String reason);

    String reject(String tenantId, String approvalNo, String operatorId, String reason);

    java.util.List<BatchApprovalResult> batchApprove(String tenantId,
                                                    java.util.List<String> approvalNos,
                                                    String operatorId,
                                                    String reason);

    java.util.List<BatchApprovalResult> batchReject(String tenantId,
                                                   java.util.List<String> approvalNos,
                                                   String operatorId,
                                                   String reason);

    record BatchApprovalResult(String approvalNo, boolean success, String message) {
    }
}
