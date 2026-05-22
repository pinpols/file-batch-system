package com.example.batch.console.infrastructure.ops;

import com.example.batch.console.application.ops.ConsoleOrchestratorProxyService;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.web.response.ops.ConsoleSchedulerSnapshotHistoryResponse;
import com.example.batch.console.web.response.ops.ConsoleSchedulerSnapshotResponse;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** {@link ConsoleOrchestratorProxyService} 的默认实现：通过 RestClient 转发请求到编排器内部接口。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleOrchestratorProxyService implements ConsoleOrchestratorProxyService {

  private static final String PARAM_TENANT_ID = "tenantId";

  private final OrchestratorInternalRestClient orchestratorInternalRestClient;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRealtimeDomainEventPublisher domainEventPublisher;

  @Override
  public Map<String, Object> instanceAction(Long id, String tenantId, String action) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    RestClient client = orchestratorInternalRestClient.build();
    return client
        .post()
        .uri("/internal/instances/{id}/{action}?tenantId={tenantId}", id, action, resolved)
        .retrieve()
        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
  }

  @Override
  public Map<String, Object> partitionAction(Long id, String tenantId, String action) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    RestClient client = orchestratorInternalRestClient.build();
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
    RestClient client = orchestratorInternalRestClient.build();
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
    RestClient client = orchestratorInternalRestClient.build();
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
    RestClient client = orchestratorInternalRestClient.build();
    return client
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/internal/scheduler/snapshot")
                    .queryParam(PARAM_TENANT_ID, tenantId)
                    .build())
        .retrieve()
        .body(ConsoleSchedulerSnapshotResponse.class);
  }

  @Override
  public List<ConsoleSchedulerSnapshotHistoryResponse> schedulerSnapshotHistory(
      String tenantId, int limit) {
    RestClient client = orchestratorInternalRestClient.build();
    return client
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/internal/scheduler/snapshot/history")
                    .queryParam(PARAM_TENANT_ID, tenantId)
                    .queryParam("limit", limit)
                    .build())
        .retrieve()
        .body(new ParameterizedTypeReference<List<ConsoleSchedulerSnapshotHistoryResponse>>() {});
  }

  @Override
  public Map<String, Integer> outboxCleanup(String tenantId, int retainDays) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    RestClient client = orchestratorInternalRestClient.build();
    return client
        .post()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/internal/outbox/cleanup")
                    .queryParam(PARAM_TENANT_ID, resolved)
                    .queryParam("retainDays", retainDays)
                    .build())
        .retrieve()
        .body(new ParameterizedTypeReference<Map<String, Integer>>() {});
  }

  @Override
  public Map<String, Integer> outboxRepublish(String tenantId, List<Long> ids) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    RestClient client = orchestratorInternalRestClient.build();
    return client
        .post()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/internal/outbox/republish")
                    .queryParam(PARAM_TENANT_ID, resolved)
                    .build())
        .body(Map.of("ids", ids == null ? List.of() : ids))
        .retrieve()
        .body(new ParameterizedTypeReference<Map<String, Integer>>() {});
  }

  @Override
  public Map<String, Object> batchDayOperate(
      String tenantId,
      String calendarCode,
      LocalDate bizDate,
      String action,
      String operatorId,
      String reason) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    RestClient client = orchestratorInternalRestClient.build();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(PARAM_TENANT_ID, resolved);
    body.put("calendarCode", calendarCode);
    body.put("bizDate", bizDate == null ? null : bizDate.toString());
    body.put("action", action);
    body.put("operatorId", operatorId);
    body.put("reason", reason);
    Map<String, Object> response =
        client
            .post()
            .uri("/internal/batch-days/operate")
            .body(body)
            .retrieve()
            .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    publishRefresh(resolved);
    return response;
  }

  @Override
  public Map<String, Object> requestForensicExport(
      String tenantId,
      LocalDate bizDateFrom,
      LocalDate bizDateTo,
      List<String> jobCodes,
      String exportFormat,
      String requestedBy) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    RestClient client = orchestratorInternalRestClient.build();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(PARAM_TENANT_ID, resolved);
    body.put("bizDateFrom", bizDateFrom == null ? null : bizDateFrom.toString());
    body.put("bizDateTo", bizDateTo == null ? null : bizDateTo.toString());
    body.put("jobCodes", jobCodes);
    body.put("exportFormat", exportFormat);
    body.put("requestedBy", requestedBy);
    return client
        .post()
        .uri("/internal/forensic/export")
        .body(body)
        .retrieve()
        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
  }

  @Override
  public byte[] downloadForensicExport(String tenantId, String exportId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    RestClient client = orchestratorInternalRestClient.build();
    return client
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/internal/forensic/export/{exportId}/download")
                    .queryParam(PARAM_TENANT_ID, resolved)
                    .build(exportId))
        .retrieve()
        .body(byte[].class);
  }

  private void publishRefresh(String tenantId) {
    domainEventPublisher.publishChanged(tenantId, "workflow-runs", "workflow-run-updated");
    domainEventPublisher.publishChanged(tenantId, "job-instances", "job-instance-updated");
    domainEventPublisher.publishSummaryRefresh(tenantId);
  }
}
