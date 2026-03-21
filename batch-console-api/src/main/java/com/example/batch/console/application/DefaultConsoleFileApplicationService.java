package com.example.batch.console.application;

import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.ArchiveFileRequest;
import com.example.batch.console.web.request.DeleteFileRequest;
import com.example.batch.console.web.request.RedispatchFileRequest;
import com.example.batch.common.constants.CommonConstants;
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
    public String archive(ArchiveFileRequest request, String idempotencyKey) {
        return executeFileOperation(request.getTenantId(), request.getFileId(), null, request.getReason(), idempotencyKey, "archive");
    }

    @Override
    public String delete(DeleteFileRequest request, String idempotencyKey) {
        return executeFileOperation(request.getTenantId(), request.getFileId(), null, request.getReason(), idempotencyKey, "delete");
    }

    @Override
    public String redispatch(RedispatchFileRequest request, String idempotencyKey) {
        return executeFileOperation(
                request.getTenantId(),
                request.getFileId(),
                request.getChannelCode(),
                request.getReason(),
                idempotencyKey,
                "redispatch"
        );
    }

    private String executeFileOperation(String tenantId,
                                        Long fileId,
                                        String channelCode,
                                        String reason,
                                        String idempotencyKey,
                                        String operation) {
        ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        FileOperationResponse response = restClient.post()
                .uri("/internal/files/{fileId}/" + operation, fileId)
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
                .body(new FileOperationRequest(
                        tenantId,
                        channelCode,
                        requestMetadata.operatorId(),
                        requestMetadata.traceId(),
                        reason
                ))
                .retrieve()
                .body(FileOperationResponse.class);
        return response == null ? null : response.status();
    }

    private record FileOperationRequest(String tenantId,
                                        String channelCode,
                                        String operatorId,
                                        String traceId,
                                        String reason) {
    }

    private record FileOperationResponse(String status) {
    }
}
