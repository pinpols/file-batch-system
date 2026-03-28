package com.example.batch.console.application;

import com.example.batch.console.web.response.ConsoleBatchApprovalResultResponse;

public interface ConsoleApprovalApplicationService {

    String approve(String tenantId, String approvalNo, String operatorId, String reason);

    String reject(String tenantId, String approvalNo, String operatorId, String reason);

    java.util.List<ConsoleBatchApprovalResultResponse> batchApprove(String tenantId,
                                                                    java.util.List<String> approvalNos,
                                                                    String operatorId,
                                                                    String reason);

    java.util.List<ConsoleBatchApprovalResultResponse> batchReject(String tenantId,
                                                                   java.util.List<String> approvalNos,
                                                                   String operatorId,
                                                                   String reason);
}
