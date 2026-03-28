package com.example.batch.console.infrastructure;

import com.example.batch.console.application.ConsoleFileApplicationService;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.ArchiveFileRequest;
import com.example.batch.console.web.request.DeleteFileRequest;
import com.example.batch.console.web.request.PresignDownloadFileRequest;
import com.example.batch.console.web.request.FileArrivalGroupActionRequest;
import com.example.batch.console.web.request.RedispatchFileRequest;
import com.example.batch.console.web.response.ConsoleFileOperationResponse;
import com.example.batch.console.web.response.ConsolePresignDownloadResponse;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class DefaultConsoleFileApplicationService implements ConsoleFileApplicationService {

    private final RestClient.Builder restClientBuilder;
    private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;

    @Override
    public ConsoleFileOperationResponse archive(ArchiveFileRequest request, String idempotencyKey) {
        return executeFileOperation(request.getTenantId(), request.getFileId(), null, request.getReason(), idempotencyKey, "archive", null);
    }

    @Override
    public ConsoleFileOperationResponse delete(DeleteFileRequest request, String idempotencyKey) {
        return executeFileOperation(request.getTenantId(), request.getFileId(), null, request.getReason(), idempotencyKey, "delete", null);
    }

    @Override
    public ConsoleFileOperationResponse redispatch(RedispatchFileRequest request, String idempotencyKey) {
        return executeFileOperation(
                request.getTenantId(),
                request.getFileId(),
                request.getChannelCode(),
                request.getReason(),
                idempotencyKey,
                "redispatch",
                null
        );
    }

    @Override
    public ConsolePresignDownloadResponse presignDownload(PresignDownloadFileRequest request, String idempotencyKey) {
        if (request.getApprovalId() == null || request.getApprovalId().isBlank()) {
            return submitApproval("DOWNLOAD", "DOWNLOAD", "FILE", String.valueOf(request.getFileId()), request, request.getReason(), idempotencyKey);
        }
        requireApprovedApproval(request.getTenantId(), request.getApprovalId());
        ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        FileDownloadResponse response = restClient.post()
                .uri("/internal/files/{fileId}/presign", request.getFileId())
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
                .body(new FileOperationRequest(
                        request.getTenantId(),
                        null,
                        ConsoleTextSanitizer.safeInput(requestMetadata.operatorId(), 64),
                        requestMetadata.traceId(),
                        ConsoleTextSanitizer.safeInput(request.getReason(), 512),
                        request.getApprovalId()
                ))
                .retrieve()
                .body(FileDownloadResponse.class);
        return response == null ? null : new ConsolePresignDownloadResponse(null, response.downloadUrl());
    }

    @Override
    public ConsoleFileOperationResponse operateArrivalGroup(FileArrivalGroupActionRequest request, String idempotencyKey) {
        ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        FileOperationResponse response = restClient.post()
                .uri("/internal/files/arrival-groups/{fileGroupCode}/actions", request.getFileGroupCode())
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
                .body(new ArrivalGroupOperationRequest(
                        request.getTenantId(),
                        request.getAction(),
                        ConsoleTextSanitizer.safeInput(requestMetadata.operatorId(), 64),
                        requestMetadata.traceId(),
                        ConsoleTextSanitizer.safeInput(request.getReason(), 512),
                        request.getExtendWaitSeconds()
                ))
                .retrieve()
                .body(FileOperationResponse.class);
        return response == null ? null : new ConsoleFileOperationResponse(response.status());
    }

    private ConsoleFileOperationResponse executeFileOperation(String tenantId,
                                                              Long fileId,
                                                              String channelCode,
                                                              String reason,
                                                              String idempotencyKey,
                                                              String operation,
                                                              String approvalId) {
        ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        FileOperationResponse response = restClient.post()
                .uri("/internal/files/{fileId}/" + operation, fileId)
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
                .body(new FileOperationRequest(
                        tenantId,
                        ConsoleTextSanitizer.safeInput(channelCode, 128),
                        ConsoleTextSanitizer.safeInput(requestMetadata.operatorId(), 64),
                        requestMetadata.traceId(),
                        ConsoleTextSanitizer.safeInput(reason, 512),
                        approvalId
                ))
                .retrieve()
                .body(FileOperationResponse.class);
        return response == null ? null : new ConsoleFileOperationResponse(response.status());
    }

    private ConsolePresignDownloadResponse submitApproval(String approvalType,
                                                          String actionType,
                                                          String targetType,
                                                          String targetId,
                                                          Object payload,
                                                          String approvalReason,
                                                          String idempotencyKey) {
        ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        ApprovalResponse response = restClient.post()
                .uri("/internal/approvals")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
                .body(new ApprovalRequest(
                        extractTenantId(payload),
                        approvalType,
                        actionType,
                        targetType,
                        targetId,
                        JsonUtils.toJson(payload),
                        ConsoleTextSanitizer.safeInput(requestMetadata.operatorId(), 64),
                        requestMetadata.traceId(),
                        idempotencyKey,
                        ConsoleTextSanitizer.safeInput(approvalReason, 512)
                ))
                .retrieve()
                .body(ApprovalResponse.class);
        if (response == null || response.approvalNo() == null || response.approvalNo().isBlank()) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "approval service returned empty response");
        }
        return new ConsolePresignDownloadResponse(response.approvalNo(), null);
    }

    private void requireApprovedApproval(String tenantId, String approvalNo) {
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        ApprovalRecordResponse response = restClient.get()
                .uri("/internal/approvals/{approvalNo}?tenantId={tenantId}", approvalNo, tenantId)
                .retrieve()
                .body(ApprovalRecordResponse.class);
        if (response == null || response.record() == null) {
            throw new BizException(ResultCode.NOT_FOUND, "approval request not found");
        }
        String status = response.record().approvalStatus();
        if (!"APPROVED".equalsIgnoreCase(status) && !"EXECUTED".equalsIgnoreCase(status)) {
            throw new BizException(ResultCode.STATE_CONFLICT, "approval is not approved yet");
        }
    }

    private String extractTenantId(Object payload) {
        if (payload instanceof PresignDownloadFileRequest request) {
            return request.getTenantId();
        }
        if (payload instanceof ArchiveFileRequest request) {
            return request.getTenantId();
        }
        if (payload instanceof DeleteFileRequest request) {
            return request.getTenantId();
        }
        if (payload instanceof RedispatchFileRequest request) {
            return request.getTenantId();
        }
        if (payload instanceof FileArrivalGroupActionRequest request) {
            return request.getTenantId();
        }
        return null;
    }

    private record ApprovalRequest(String tenantId,
                                   String approvalType,
                                   String actionType,
                                   String targetType,
                                   String targetId,
                                   String payloadJson,
                                   String requesterId,
                                   String sourceTraceId,
                                   String sourceIdempotencyKey,
                                   String approvalReason) {
    }

    private record ApprovalResponse(String approvalNo) {
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

    private record FileOperationRequest(String tenantId,
                                        String channelCode,
                                        String operatorId,
                                        String traceId,
                                        String reason,
                                        String approvalId) {
    }

    private record FileOperationResponse(String status) {
    }

    private record FileDownloadResponse(String downloadUrl) {
    }

    private record ArrivalGroupOperationRequest(String tenantId,
                                                String action,
                                                String operatorId,
                                                String traceId,
                                                String reason,
                                                Long extendWaitSeconds) {
    }
}
