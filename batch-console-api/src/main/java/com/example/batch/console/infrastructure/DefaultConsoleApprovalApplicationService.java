package com.example.batch.console.infrastructure;

import com.example.batch.console.application.ConsoleApprovalApplicationService;
import com.example.batch.console.application.ConsoleFileApplicationService;
import com.example.batch.console.application.ConsoleJobApplicationService;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.web.request.CompensationCommandRequest;
import com.example.batch.console.web.request.DeadLetterReplayRequest;
import com.example.batch.console.web.request.PresignDownloadFileRequest;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class DefaultConsoleApprovalApplicationService implements ConsoleApprovalApplicationService {

    private final RestClient.Builder restClientBuilder;
    private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;
    private final ConsoleJobApplicationService consoleJobApplicationService;
    private final ConsoleFileApplicationService consoleFileApplicationService;

    @Override
    public String approve(String tenantId, String approvalNo, String operatorId, String reason) {
        ApprovalRecordResponse recordResponse = loadApproval(tenantId, approvalNo);
        ApprovalRecordResponse.ApprovalRecord record = recordResponse.record();
        if (!"PENDING".equalsIgnoreCase(record.approvalStatus())) {
            return approvalNo;
        }
        approveRemote(tenantId, approvalNo, operatorId, reason);
        String actionType = record.actionType();
        String result = switch (actionType) {
            case "COMPENSATION" -> {
                CompensationCommandRequest request = JsonUtils.fromJson(record.payloadJson(), CompensationCommandRequest.class);
                request.setApprovalId(approvalNo);
                yield consoleJobApplicationService.compensation(request, approvalNo);
            }
            case "DLQ_REPLAY" -> {
                DeadLetterReplayRequest request = JsonUtils.fromJson(record.payloadJson(), DeadLetterReplayRequest.class);
                request.setApprovalId(approvalNo);
                yield consoleJobApplicationService.replayDeadLetter(request, approvalNo);
            }
            case "DOWNLOAD" -> {
                PresignDownloadFileRequest request = JsonUtils.fromJson(record.payloadJson(), PresignDownloadFileRequest.class);
                request.setApprovalId(approvalNo);
                yield consoleFileApplicationService.presignDownload(request, approvalNo);
            }
            case "CATCH_UP" -> {
                ConsoleCatchUpApprovalRequest request = JsonUtils.fromJson(record.payloadJson(), ConsoleCatchUpApprovalRequest.class);
                request.setApprovalId(approvalNo);
                yield consoleJobApplicationService.approveCatchUp(request, approvalNo);
            }
            default -> throw new BizException(ResultCode.INVALID_ARGUMENT, "unsupported approval action: " + actionType);
        };
        markExecutedRemote(tenantId, approvalNo);
        return result;
    }

    @Override
    public String reject(String tenantId, String approvalNo, String operatorId, String reason) {
        rejectRemote(tenantId, approvalNo, operatorId, reason);
        return approvalNo;
    }

    private ApprovalRecordResponse loadApproval(String tenantId, String approvalNo) {
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        ApprovalRecordResponse response = restClient.get()
                .uri("/internal/approvals/{approvalNo}?tenantId={tenantId}", approvalNo, tenantId)
                .retrieve()
                .body(ApprovalRecordResponse.class);
        if (response == null || response.record() == null) {
            throw new BizException(ResultCode.NOT_FOUND, "approval request not found");
        }
        return response;
    }

    private void approveRemote(String tenantId, String approvalNo, String operatorId, String reason) {
        ConsoleRequestMetadata metadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        restClient.post()
                .uri("/internal/approvals/{approvalNo}/approve", approvalNo)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, metadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, metadata.traceId())
                .body(new ApprovalActionRequest(tenantId, operatorId, reason))
                .retrieve()
                .toBodilessEntity();
    }

    private void rejectRemote(String tenantId, String approvalNo, String operatorId, String reason) {
        ConsoleRequestMetadata metadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        restClient.post()
                .uri("/internal/approvals/{approvalNo}/reject", approvalNo)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, metadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, metadata.traceId())
                .body(new ApprovalActionRequest(tenantId, operatorId, reason))
                .retrieve()
                .toBodilessEntity();
    }

    private void markExecutedRemote(String tenantId, String approvalNo) {
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        restClient.post()
                .uri("/internal/approvals/{approvalNo}/executed", approvalNo)
                .body(new ApprovalTenantRequest(tenantId))
                .retrieve()
                .toBodilessEntity();
    }

    private record ApprovalActionRequest(String tenantId, String operatorId, String reason) {
    }

    private record ApprovalTenantRequest(String tenantId) {
    }

    private record ApprovalRecordResponse(ApprovalRecord record) {
        private record ApprovalRecord(String tenantId,
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
                                      String sourceIdempotencyKey) {
        }
    }
}
