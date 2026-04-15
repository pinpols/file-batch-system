package com.example.batch.console.infrastructure;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.console.application.ConsoleWorkerApplicationService;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.DrainWorkerRequest;
import com.example.batch.console.web.request.ForceOfflineWorkerRequest;
import com.example.batch.console.web.response.ConsoleWorkerClaimedTaskResponse;
import com.example.batch.console.web.response.ConsoleWorkerRegistryResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * {@link com.example.batch.console.application.ConsoleWorkerApplicationService} 的默认实现：调用编排器 Worker
 * 运维接口。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleWorkerApplicationService implements ConsoleWorkerApplicationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_WORKERS = "workers";
  private static final String KEY_TENANT_ID = "tenantId";

  private final RestClient.Builder restClientBuilder;
  private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRealtimeDomainEventPublisher domainEventPublisher;
  private final Environment environment;

  @Override
  public ConsoleWorkerRegistryResponse drain(
      String workerCode, DrainWorkerRequest request, String idempotencyKey) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    ConsoleRequestMetadata meta = requestMetadataResolver.current();
    RestClient client =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(KEY_TENANT_ID, tenantId);
    if (request.getTimeoutSeconds() != null) {
      body.put("timeoutSeconds", request.getTimeoutSeconds());
    }
    ConsoleWorkerRegistryResponse response =
        toResponse(
            client
                .post()
                .uri("/internal/workers/{workerCode}/drain", workerCode)
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, meta.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, meta.traceId())
                .body(body)
                .retrieve()
                .body(ConsoleWorkerRegistryResponse.class));
    domainEventPublisher.publishChanged(tenantId, KEY_WORKERS, "worker-updated");
    domainEventPublisher.publishSummaryRefresh(tenantId);
    return response;
  }

  @Override
  public ConsoleWorkerRegistryResponse forceOffline(
      String workerCode, ForceOfflineWorkerRequest request, String idempotencyKey) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    ConsoleRequestMetadata meta = requestMetadataResolver.current();
    RestClient client =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    ConsoleWorkerRegistryResponse response =
        toResponse(
            client
                .post()
                .uri("/internal/workers/{workerCode}/force-offline", workerCode)
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, meta.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, meta.traceId())
                .body(Map.of(KEY_TENANT_ID, tenantId))
                .retrieve()
                .body(ConsoleWorkerRegistryResponse.class));
    domainEventPublisher.publishChanged(tenantId, KEY_WORKERS, "worker-updated");
    domainEventPublisher.publishSummaryRefresh(tenantId);
    return response;
  }

  @Override
  public ConsoleWorkerRegistryResponse takeover(
      String workerCode, ForceOfflineWorkerRequest request, String idempotencyKey) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    ConsoleRequestMetadata meta = requestMetadataResolver.current();
    RestClient client =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    ConsoleWorkerRegistryResponse response =
        toResponse(
            client
                .post()
                .uri("/internal/workers/{workerCode}/takeover", workerCode)
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, meta.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, meta.traceId())
                .body(Map.of(KEY_TENANT_ID, tenantId))
                .retrieve()
                .body(ConsoleWorkerRegistryResponse.class));
    domainEventPublisher.publishChanged(tenantId, KEY_WORKERS, "worker-updated");
    domainEventPublisher.publishSummaryRefresh(tenantId);
    return response;
  }

  @Override
  public List<ConsoleWorkerClaimedTaskResponse> claimedTasks(String tenantId, String workerCode) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    ConsoleRequestMetadata meta = requestMetadataResolver.current();
    RestClient client =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    List<ConsoleWorkerClaimedTaskResponse> tasks =
        client
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder
                        .path("/internal/workers/{workerCode}/claimed-tasks")
                        .queryParam(KEY_TENANT_ID, resolved)
                        .build(workerCode))
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, meta.requestId())
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, meta.traceId())
            .retrieve()
            .body(new ParameterizedTypeReference<List<ConsoleWorkerClaimedTaskResponse>>() {});
    return tasks == null ? List.of() : tasks;
  }

  @Override
  public ConsoleWorkerRegistryResponse warmup(
      String workerCode, String tenantId, String idempotencyKey) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    ConsoleRequestMetadata meta = requestMetadataResolver.current();
    RestClient client =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    ConsoleWorkerRegistryResponse response =
        toResponse(
            client
                .post()
                .uri("/internal/workers/{workerCode}/warmup", workerCode)
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, meta.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, meta.traceId())
                .body(Map.of(KEY_TENANT_ID, resolved))
                .retrieve()
                .body(ConsoleWorkerRegistryResponse.class));
    domainEventPublisher.publishChanged(resolved, KEY_WORKERS, "worker-warmup");
    return response;
  }

  private ConsoleWorkerRegistryResponse toResponse(ConsoleWorkerRegistryResponse response) {
    return response;
  }

  private String resolveUrl(String url) {
    return environment.resolvePlaceholders(url);
  }
}
