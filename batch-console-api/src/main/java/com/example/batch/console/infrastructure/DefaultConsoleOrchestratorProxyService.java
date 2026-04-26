package com.example.batch.console.infrastructure;

import com.example.batch.console.application.ConsoleOrchestratorProxyService;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.response.ConsoleSchedulerSnapshotHistoryResponse;
import com.example.batch.console.web.response.ConsoleSchedulerSnapshotResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** {@link ConsoleOrchestratorProxyService} 的默认实现：通过 RestClient 转发请求到编排器内部接口。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleOrchestratorProxyService implements ConsoleOrchestratorProxyService {

  private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
  private final RestClient.Builder restClientBuilder;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRealtimeDomainEventPublisher domainEventPublisher;
  private final Environment environment;

  @Override
  public Map<String, Object> instanceAction(Long id, String tenantId, String action) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    RestClient client =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    return client
        .post()
        .uri("/internal/instances/{id}/{action}?tenantId={tenantId}", id, action, resolved)
        .retrieve()
        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
  }

  @Override
  public Map<String, Object> partitionAction(Long id, String tenantId, String action) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    RestClient client =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    return client
        .post()
        .uri(
            "/internal/instances/partitions/{id}/{action}?tenantId={tenantId}",
            id,
            action,
            resolved)
        .retrieve()
        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
  }

  @Override
  public Map<String, Object> workflowRunAction(Long id, String tenantId, String action) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    RestClient client =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    Map<String, Object> response =
        client
            .post()
            .uri("/internal/workflow-runs/{id}/{action}?tenantId={tenantId}", id, action, resolved)
            .retrieve()
            .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    publishRefresh(resolved);
    return response;
  }

  @Override
  public Map<String, Object> workflowRunSkipNode(Long id, String tenantId, String nodeCode) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    RestClient client =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    Map<String, Object> response =
        client
            .post()
            .uri(
                "/internal/workflow-runs/{id}/skip-node?tenantId={tenantId}&nodeCode={nodeCode}",
                id,
                resolved,
                nodeCode)
            .retrieve()
            .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    publishRefresh(resolved);
    return response;
  }

  @Override
  public ConsoleSchedulerSnapshotResponse schedulerSnapshot(String tenantId) {
    RestClient client =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    return client
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/internal/scheduler/snapshot")
                    .queryParam("tenantId", tenantId)
                    .build())
        .retrieve()
        .body(ConsoleSchedulerSnapshotResponse.class);
  }

  private String resolveUrl(String url) {
    return environment.resolvePlaceholders(url);
  }

  @Override
  public List<ConsoleSchedulerSnapshotHistoryResponse> schedulerSnapshotHistory(
      String tenantId, int limit) {
    RestClient client =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    return client
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/internal/scheduler/snapshot/history")
                    .queryParam("tenantId", tenantId)
                    .queryParam("limit", limit)
                    .build())
        .retrieve()
        .body(new ParameterizedTypeReference<List<ConsoleSchedulerSnapshotHistoryResponse>>() {});
  }

  @Override
  public Map<String, Integer> outboxCleanup(String tenantId, int retainDays) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    RestClient client =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    return client
        .post()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/internal/outbox/cleanup")
                    .queryParam("tenantId", resolved)
                    .queryParam("retainDays", retainDays)
                    .build())
        .retrieve()
        .body(new ParameterizedTypeReference<Map<String, Integer>>() {});
  }

  @Override
  public Map<String, Integer> outboxRepublish(String tenantId, List<Long> ids) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    RestClient client =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    return client
        .post()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/internal/outbox/republish")
                    .queryParam("tenantId", resolved)
                    .build())
        .body(Map.of("ids", ids == null ? List.of() : ids))
        .retrieve()
        .body(new ParameterizedTypeReference<Map<String, Integer>>() {});
  }

  private void publishRefresh(String tenantId) {
    domainEventPublisher.publishChanged(tenantId, "workflow-runs", "workflow-run-updated");
    domainEventPublisher.publishChanged(tenantId, "job-instances", "job-instance-updated");
    domainEventPublisher.publishSummaryRefresh(tenantId);
  }
}
