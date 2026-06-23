package io.github.pinpols.batch.console.domain.ops.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.resilience.DownstreamFallback;
import io.github.pinpols.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEventPublisher;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleOrchestratorProxyService;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleSchedulerSnapshotHistoryResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleSchedulerSnapshotResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * {@link ConsoleOrchestratorProxyService} 的默认实现：通过 RestClient 转发请求到编排器内部接口。
 *
 * <p>P1-B(2026-05-30):全部调用走 {@link DownstreamFallback} 统一打 metrics。读路径 {@code
 * scheduler-snapshot-history} 用 {@code callOrFallback} 降级为空 list;{@code scheduler-snapshot} 因为强类型响应
 * + 不接受空对象,改 fail-fast 让 FE 显示真实错误。写路径全部 {@code callOrThrow}。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleOrchestratorProxyService implements ConsoleOrchestratorProxyService {

  private static final String SVC = "orchestrator";
  private static final String PARAM_TENANT_ID = "tenantId";

  private final OrchestratorInternalRestClient orchestratorInternalRestClient;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRealtimeDomainEventPublisher domainEventPublisher;
  private final DownstreamFallback downstreamFallback;
  private final ConsoleQueryCacheService cacheService;

  @Override
  public Map<String, Object> instanceAction(Long id, String tenantId, String action) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "instance-action",
        () ->
            orchestratorInternalRestClient
                .build()
                .post()
                .uri("/internal/instances/{id}/{action}?tenantId={tenantId}", id, action, resolved)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {}));
  }

  @Override
  public Map<String, Object> partitionAction(Long id, String tenantId, String action) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "partition-action",
        () ->
            orchestratorInternalRestClient
                .build()
                .post()
                .uri(
                    "/internal/instances/partitions/{id}/{action}?tenantId={tenantId}",
                    id,
                    action,
                    resolved)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {}));
  }

  @Override
  public Map<String, Object> workflowRunAction(Long id, String tenantId, String action) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> response =
        downstreamFallback.callOrThrow(
            SVC,
            "workflow-run-action",
            () ->
                orchestratorInternalRestClient
                    .build()
                    .post()
                    .uri(
                        "/internal/workflow-runs/{id}/{action}?tenantId={tenantId}",
                        id,
                        action,
                        resolved)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {}));
    publishRefresh(resolved);
    return response;
  }

  @Override
  public Map<String, Object> workflowRunSkipNode(Long id, String tenantId, String nodeCode) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> response =
        downstreamFallback.callOrThrow(
            SVC,
            "workflow-run-skip-node",
            () ->
                orchestratorInternalRestClient
                    .build()
                    .post()
                    .uri(
                        "/internal/workflow-runs/{id}/skip-node?tenantId={tenantId}&nodeCode={nodeCode}",
                        id,
                        resolved,
                        nodeCode)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {}));
    publishRefresh(resolved);
    return response;
  }

  @Override
  public ConsoleSchedulerSnapshotResponse schedulerSnapshot(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "snapshot:" + ConsoleQueryCacheService.keySegment(resolved),
        ConsoleQueryCacheService.SNAPSHOT_TTL,
        ConsoleSchedulerSnapshotResponse.class,
        () -> loadSchedulerSnapshot(resolved));
  }

  private ConsoleSchedulerSnapshotResponse loadSchedulerSnapshot(String resolved) {
    // 强类型响应,FE 不接受空对象 → fail-fast(用 callOrThrow 统一 metrics)。
    return downstreamFallback.callOrThrow(
        SVC,
        "scheduler-snapshot",
        () ->
            orchestratorInternalRestClient
                .build()
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/internal/scheduler/snapshot")
                            .queryParam(PARAM_TENANT_ID, resolved)
                            .build())
                .retrieve()
                .body(ConsoleSchedulerSnapshotResponse.class));
  }

  @Override
  public List<ConsoleSchedulerSnapshotHistoryResponse> schedulerSnapshotHistory(
      String tenantId, int limit) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "snapshot:" + ConsoleQueryCacheService.keySegment(resolved) + ":history:" + limit,
        ConsoleQueryCacheService.SNAPSHOT_TTL,
        new TypeReference<List<ConsoleSchedulerSnapshotHistoryResponse>>() {},
        () -> loadSchedulerSnapshotHistory(resolved, limit));
  }

  private List<ConsoleSchedulerSnapshotHistoryResponse> loadSchedulerSnapshotHistory(
      String resolved, int limit) {
    return downstreamFallback.callOrFallback(
        SVC,
        "scheduler-snapshot-history",
        () ->
            orchestratorInternalRestClient
                .build()
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/internal/scheduler/snapshot/history")
                            .queryParam(PARAM_TENANT_ID, resolved)
                            .queryParam("limit", limit)
                            .build())
                .retrieve()
                .body(
                    new ParameterizedTypeReference<
                        List<ConsoleSchedulerSnapshotHistoryResponse>>() {}),
        ex -> List.of());
  }

  @Override
  public Map<String, Integer> outboxCleanup(String tenantId, int retainDays) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "outbox-cleanup",
        () ->
            orchestratorInternalRestClient
                .build()
                .post()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/internal/outbox/cleanup")
                            .queryParam(PARAM_TENANT_ID, resolved)
                            .queryParam("retainDays", retainDays)
                            .build())
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Integer>>() {}));
  }

  @Override
  public Map<String, Integer> outboxRepublish(String tenantId, List<Long> ids) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "outbox-republish",
        () ->
            orchestratorInternalRestClient
                .build()
                .post()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/internal/outbox/republish")
                            .queryParam(PARAM_TENANT_ID, resolved)
                            .build())
                .body(Map.of("ids", ids == null ? List.of() : ids))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Integer>>() {}));
  }

  @Override
  public Map<String, Integer> adminTestDataCleanupByPrefix(String prefix) {
    return downstreamFallback.callOrThrow(
        SVC,
        "admin-test-data-cleanup",
        () ->
            orchestratorInternalRestClient
                .build()
                .delete()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/internal/admin/test-data")
                            .queryParam("prefix", prefix)
                            .build())
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Integer>>() {}));
  }

  @Override
  public Map<String, Integer> adminTestDataCleanupByExactTenantIds(List<String> tenantIds) {
    String ids = tenantIds == null ? "" : String.join(",", tenantIds);
    return downstreamFallback.callOrThrow(
        SVC,
        "admin-test-data-cleanup-by-ids",
        () ->
            orchestratorInternalRestClient
                .build()
                .delete()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/internal/admin/test-data/by-ids")
                            .queryParam("ids", ids)
                            .build())
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Integer>>() {}));
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
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(PARAM_TENANT_ID, resolved);
    body.put("calendarCode", calendarCode);
    body.put("bizDate", bizDate == null ? null : bizDate.toString());
    body.put("action", action);
    body.put("operatorId", operatorId);
    body.put("reason", reason);
    Map<String, Object> response =
        downstreamFallback.callOrThrow(
            SVC,
            "batch-day-operate",
            () ->
                orchestratorInternalRestClient
                    .build()
                    .post()
                    .uri("/internal/batch-days/operate")
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {}));
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
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(PARAM_TENANT_ID, resolved);
    body.put("bizDateFrom", bizDateFrom == null ? null : bizDateFrom.toString());
    body.put("bizDateTo", bizDateTo == null ? null : bizDateTo.toString());
    body.put("jobCodes", jobCodes);
    body.put("exportFormat", exportFormat);
    body.put("requestedBy", requestedBy);
    return downstreamFallback.callOrThrow(
        SVC,
        "forensic-export-request",
        () ->
            orchestratorInternalRestClient
                .build()
                .post()
                .uri("/internal/forensic/export")
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {}));
  }

  @Override
  public byte[] downloadForensicExport(String tenantId, String exportId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "forensic-export-download",
        () ->
            orchestratorInternalRestClient
                .build()
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/internal/forensic/export/{exportId}/download")
                            .queryParam(PARAM_TENANT_ID, resolved)
                            .build(exportId))
                .retrieve()
                .body(byte[].class));
  }

  @Override
  public List<Map<String, Object>> pipelineProgress(String tenantId, List<String> workerCodes) {
    if (workerCodes == null || workerCodes.isEmpty()) {
      return List.of();
    }
    String resolved = tenantGuard.resolveTenant(tenantId);
    String workerCodesParam = String.join(",", workerCodes);
    return downstreamFallback.callOrFallback(
        SVC,
        "pipeline-progress",
        () ->
            orchestratorInternalRestClient
                .build()
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/internal/pipeline-progress")
                            .queryParam(PARAM_TENANT_ID, resolved)
                            .queryParam("workerCodes", workerCodesParam)
                            .build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {}),
        ex -> List.of());
  }

  private void publishRefresh(String tenantId) {
    domainEventPublisher.publishChanged(tenantId, "workflow-runs", "workflow-run-updated");
    domainEventPublisher.publishChanged(tenantId, "job-instances", "job-instance-updated");
    domainEventPublisher.publishSummaryRefresh(tenantId);
  }
}
