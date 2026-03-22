package com.example.batch.console.infrastructure;

import com.example.batch.console.service.ConsoleWorkerApplicationService;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.domain.request.DrainWorkerRequest;
import com.example.batch.console.domain.request.ForceOfflineWorkerRequest;
import com.example.batch.common.constants.CommonConstants;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class DefaultConsoleWorkerApplicationService implements ConsoleWorkerApplicationService {

    private final RestClient.Builder restClientBuilder;
    private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;
    private final ConsoleTenantGuard tenantGuard;

    @Override
    public Map<String, Object> drain(String workerCode, DrainWorkerRequest request, String idempotencyKey) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        ConsoleRequestMetadata meta = requestMetadataResolver.current();
        RestClient client = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        if (request.getTimeoutSeconds() != null) {
            body.put("timeoutSeconds", request.getTimeoutSeconds());
        }
        return client.post()
                .uri("/internal/workers/{workerCode}/drain", workerCode)
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, meta.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, meta.traceId())
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }

    @Override
    public Map<String, Object> forceOffline(String workerCode, ForceOfflineWorkerRequest request, String idempotencyKey) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        ConsoleRequestMetadata meta = requestMetadataResolver.current();
        RestClient client = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        return client.post()
                .uri("/internal/workers/{workerCode}/force-offline", workerCode)
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, meta.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, meta.traceId())
                .body(Map.of("tenantId", tenantId))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }

    @Override
    public List<Map<String, Object>> claimedTasks(String tenantId, String workerCode) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        ConsoleRequestMetadata meta = requestMetadataResolver.current();
        RestClient client = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        return client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/workers/{workerCode}/claimed-tasks")
                        .queryParam("tenantId", resolved)
                        .build(workerCode))
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, meta.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, meta.traceId())
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                });
    }
}
