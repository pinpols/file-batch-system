package com.example.batch.console.application;

public interface ConsoleApprovalApplicationService {

    String approve(String tenantId, String approvalNo, String operatorId, String reason);

    String reject(String tenantId, String approvalNo, String operatorId, String reason);
}
