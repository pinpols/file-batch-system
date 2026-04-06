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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import lombok.Setter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * {@link com.example.batch.console.application.ConsoleFileApplicationService} 的默认实现：转发编排器文件治理 API。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleFileApplicationService implements ConsoleFileApplicationService {

    private final RestClient.Builder restClientBuilder;
    private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;
    private final Environment environment;

    @Override
    public ConsoleFileOperationResponse archive(ArchiveFileRequest request, String idempotencyKey) {
        return executeFileOperation(new FileExecContext(request.getTenantId(), request.getFileId(), null, request.getReason(), idempotencyKey, "archive", null));
    }

    @Override
    public ConsoleFileOperationResponse delete(DeleteFileRequest request, String idempotencyKey) {
        return executeFileOperation(new FileExecContext(request.getTenantId(), request.getFileId(), null, request.getReason(), idempotencyKey, "delete", null));
    }

    @Override
    public ConsoleFileOperationResponse redispatch(RedispatchFileRequest request, String idempotencyKey) {
        return executeFileOperation(new FileExecContext(request.getTenantId(), request.getFileId(), request.getChannelCode(), request.getReason(), idempotencyKey, "redispatch", null));
    }

    @Override
    public ConsolePresignDownloadResponse presignDownload(PresignDownloadFileRequest request, String idempotencyKey) {
        if (request.getApprovalId() == null || request.getApprovalId().isBlank()) {
            return submitApproval(new ApprovalSubmitContext("DOWNLOAD", "DOWNLOAD", "FILE", String.valueOf(request.getFileId()), request, request.getReason(), idempotencyKey));
        }
        requireApprovedApproval(request.getTenantId(), request.getApprovalId());
        ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
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
        RestClient restClient = restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
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

    private record FileExecContext(String tenantId, Long fileId, String channelCode, String reason,
                                   String idempotencyKey, String operation, String approvalId) {}

    private record ApprovalSubmitContext(String approvalType, String actionType, String targetType, String targetId,
                                         Object payload, String approvalReason, String idempotencyKey) {}

    private ConsoleFileOperationResponse executeFileOperation(FileExecContext ctx) {
        ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
        FileOperationResponse response = restClient.post()
                .uri("/internal/files/{fileId}/" + ctx.operation(), ctx.fileId())
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, ctx.idempotencyKey())
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
                .body(new FileOperationRequest(
                        ctx.tenantId(),
                        ConsoleTextSanitizer.safeInput(ctx.channelCode(), 128),
                        ConsoleTextSanitizer.safeInput(requestMetadata.operatorId(), 64),
                        requestMetadata.traceId(),
                        ConsoleTextSanitizer.safeInput(ctx.reason(), 512),
                        ctx.approvalId()
                ))
                .retrieve()
                .body(FileOperationResponse.class);
        return response == null ? null : new ConsoleFileOperationResponse(response.status());
    }

    private ConsolePresignDownloadResponse submitApproval(ApprovalSubmitContext ctx) {
        ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
        ApprovalResponse response = restClient.post()
                .uri("/internal/approvals")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, ctx.idempotencyKey())
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
                .body(ApprovalRequest.of(
                        new ApprovalTarget(
                                extractTenantId(ctx.payload()),
                                ctx.approvalType(),
                                ctx.actionType(),
                                ctx.targetType(),
                                ctx.targetId()
                        ),
                        ctx.payload(),
                        requestMetadata,
                        ctx.idempotencyKey(),
                        ctx.approvalReason()
                ))
                .retrieve()
                .body(ApprovalResponse.class);
        if (response == null || response.approvalNo() == null || response.approvalNo().isBlank()) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "approval service returned empty response");
        }
        return new ConsolePresignDownloadResponse(response.approvalNo(), null);
    }

    private void requireApprovedApproval(String tenantId, String approvalNo) {
        RestClient restClient = restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
        ApprovalRecordResponse response = restClient.get()
                .uri("/internal/approvals/{approvalNo}?tenantId={tenantId}", approvalNo, tenantId)
                .retrieve()
                .body(ApprovalRecordResponse.class);
        if (response == null || response.getRecord() == null) {
            throw new BizException(ResultCode.NOT_FOUND, "approval request not found");
        }
        String status = response.getRecord().getApprovalStatus();
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

    private record ApprovalTarget(String tenantId,
                                  String approvalType,
                                  String actionType,
                                  String targetType,
                                  String targetId) {
    }

    @Getter
    private static final class ApprovalRequest {
        private final String tenantId;
        private final String approvalType;
        private final String actionType;
        private final String targetType;
        private final String targetId;
        private final String payloadJson;
        private final String requesterId;
        private final String sourceTraceId;
        private final String sourceIdempotencyKey;
        private final String approvalReason;

        private ApprovalRequest(ApprovalTarget target,
                                String payloadJson,
                                String requesterId,
                                String sourceTraceId,
                                String sourceIdempotencyKey,
                                String approvalReason) {
            this.tenantId = target.tenantId();
            this.approvalType = target.approvalType();
            this.actionType = target.actionType();
            this.targetType = target.targetType();
            this.targetId = target.targetId();
            this.payloadJson = payloadJson;
            this.requesterId = requesterId;
            this.sourceTraceId = sourceTraceId;
            this.sourceIdempotencyKey = sourceIdempotencyKey;
            this.approvalReason = approvalReason;
        }

        private static ApprovalRequest of(ApprovalTarget target,
                                          Object payload,
                                          ConsoleRequestMetadata metadata,
                                          String idempotencyKey,
                                          String approvalReason) {
            return new ApprovalRequest(
                    target,
                    JsonUtils.toJson(payload),
                    ConsoleTextSanitizer.safeInput(metadata.operatorId(), 64),
                    metadata.traceId(),
                    idempotencyKey,
                    ConsoleTextSanitizer.safeInput(approvalReason, 512)
            );
        }
    }

    private record ApprovalResponse(String approvalNo) {
    }

    @Getter
    @Setter
    @NoArgsConstructor
    private static class ApprovalRecordResponse {
        private ApprovalRecord record;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    private static class ApprovalRecord {
        private String tenantId;
        private String approvalNo;
        private String approvalType;
        private String actionType;
        private String targetType;
        private String targetId;
        private String payloadJson;
        private String approvalStatus;
        private String requesterId;
        private String approverId;
        private String rejectionReason;
        private String approvalReason;
        private String sourceTraceId;
        private String sourceIdempotencyKey;
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

    private String resolveUrl(String url) {
        return environment.resolvePlaceholders(url);
    }
}
