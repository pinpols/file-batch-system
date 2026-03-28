package com.example.batch.console.infrastructure;

import com.example.batch.console.application.ConsoleWorkerApplicationService;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.DrainWorkerRequest;
import com.example.batch.console.web.request.ForceOfflineWorkerRequest;
import com.example.batch.console.web.response.ConsoleWorkerClaimedTaskResponse;
import com.example.batch.console.web.response.ConsoleWorkerRegistryResponse;
import com.example.batch.common.constants.CommonConstants;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * {@link com.example.batch.console.application.ConsoleWorkerApplicationService} 的默认实现：调用编排器 Worker 运维接口。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleWorkerApplicationService implements ConsoleWorkerApplicationService {

    private final RestClient.Builder restClientBuilder;
    private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;
    private final ConsoleTenantGuard tenantGuard;

    @Override
    public ConsoleWorkerRegistryResponse drain(String workerCode, DrainWorkerRequest request, String idempotencyKey) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        ConsoleRequestMetadata meta = requestMetadataResolver.current();
        RestClient client = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        if (request.getTimeoutSeconds() != null) {
            body.put("timeoutSeconds", request.getTimeoutSeconds());
        }
        return toResponse(client.post()
                .uri("/internal/workers/{workerCode}/drain", workerCode)
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, meta.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, meta.traceId())
                .body(body)
                .retrieve()
                .body(ConsoleWorkerRegistryResponse.class));
    }

    @Override
    public ConsoleWorkerRegistryResponse forceOffline(String workerCode, ForceOfflineWorkerRequest request, String idempotencyKey) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        ConsoleRequestMetadata meta = requestMetadataResolver.current();
        RestClient client = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        return toResponse(client.post()
                .uri("/internal/workers/{workerCode}/force-offline", workerCode)
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, meta.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, meta.traceId())
                .body(Map.of("tenantId", tenantId))
                .retrieve()
                .body(ConsoleWorkerRegistryResponse.class));
    }

    @Override
    public List<ConsoleWorkerClaimedTaskResponse> claimedTasks(String tenantId, String workerCode) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        ConsoleRequestMetadata meta = requestMetadataResolver.current();
        RestClient client = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        List<com.example.batch.orchestrator.domain.entity.JobTaskEntity> tasks = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/workers/{workerCode}/claimed-tasks")
                        .queryParam("tenantId", resolved)
                        .build(workerCode))
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, meta.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, meta.traceId())
                .retrieve()
                .body(new ParameterizedTypeReference<List<com.example.batch.orchestrator.domain.entity.JobTaskEntity>>() {
                });
        return tasks == null ? List.of() : tasks.stream().map(this::toResponse).toList();
    }

    private ConsoleWorkerRegistryResponse toResponse(ConsoleWorkerRegistryResponse response) {
        return response;
    }

    private ConsoleWorkerClaimedTaskResponse toResponse(com.example.batch.orchestrator.domain.entity.JobTaskEntity task) {
        if (task == null) {
            return null;
        }
        return new ConsoleWorkerClaimedTaskResponse(
                task.getId(),
                task.getTenantId(),
                task.getJobInstanceId(),
                task.getJobPartitionId(),
                task.getTaskType(),
                task.getTaskSeq(),
                task.getTaskStatus(),
                task.getAssignedWorkerCode(),
                task.getTaskPayload(),
                task.getResultSummary(),
                task.getErrorCode(),
                task.getErrorMessage(),
                task.getStartedAt(),
                task.getFinishedAt(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
