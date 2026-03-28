package com.example.batch.console.application;

import com.example.batch.console.web.response.ConsoleBatchApprovalResultResponse;

/**
 * 控制台审批应用服务：单条审批通过/拒绝及批量审批。
 */
public interface ConsoleApprovalApplicationService {

    /** 审批通过指定审批单。 */
    String approve(String tenantId, String approvalNo, String operatorId, String reason);

    /** 审批拒绝指定审批单。 */
    String reject(String tenantId, String approvalNo, String operatorId, String reason);

    /** 批量审批通过，返回每条结果。 */
    java.util.List<ConsoleBatchApprovalResultResponse> batchApprove(String tenantId,
                                                                    java.util.List<String> approvalNos,
                                                                    String operatorId,
                                                                    String reason);

    /** 批量审批拒绝，返回每条结果。 */
    java.util.List<ConsoleBatchApprovalResultResponse> batchReject(String tenantId,
                                                                   java.util.List<String> approvalNos,
                                                                   String operatorId,
                                                                   String reason);
}
