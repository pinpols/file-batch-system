package io.github.pinpols.batch.console.application.ops.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.resilience.DownstreamFallback;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.contract.response.ops.AssetPartitionReadinessResponse;
import io.github.pinpols.batch.console.application.contract.response.ops.CapacityProfileResponse;
import io.github.pinpols.batch.console.application.contract.response.ops.LineageEvidenceResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.application.ops.response.ConsoleBatchDayOperateResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsoleInstanceActionResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsolePartitionActionResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsoleRetryFailedPartitionsResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsoleWorkflowRunActionResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsoleWorkflowRunSkipNodeResponse;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchDayReplaySubmitRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.DryRunPlanRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayReplayEntryResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayReplayPreviewResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayReplaySessionResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleDryRunPlanResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleResultVersionResponse;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleForensicExportResponse;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OutboxCleanupProxyResponse;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OutboxRepublishProxyResponse;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.shared.view.ConsolePipelineProgressItemResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleSchedulerSnapshotHistoryResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleSchedulerSnapshotResponse;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriBuilder;

/**
 * {@link ConsoleOrchestratorPort} 的默认实现：通过 RestClient 转发请求到编排器内部接口。
 *
 * <p>P1-B(2026-05-30):全部调用走 {@link DownstreamFallback} 统一打 metrics。读路径 {@code
 * scheduler-snapshot-history} 用 {@code callOrFallback} 降级为空 list;{@code scheduler-snapshot} 因为强类型响应
 * + 不接受空对象,改 fail-fast 让 FE 显示真实错误。写路径全部 {@code callOrThrow}。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleOrchestratorProxyService implements ConsoleOrchestratorPort {

  private static final String SVC = "orchestrator";
  private static final String PARAM_TENANT_ID = "tenantId";

  private final OrchestratorInternalRestClient orchestratorInternalRestClient;
  private final TenantIdResolver tenantGuard;
  private final ConsoleRealtimeEventPort domainEventPublisher;
  private final DownstreamFallback downstreamFallback;
  private final ConsoleQueryCacheService cacheService;

  @Override
  public ConsoleInstanceActionResponse instanceAction(Long id, String tenantId, String action) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "instance-action",
        () -> orchestratorInternalRestClient
            .client()
            .post()
            .uri("/internal/instances/{id}/{action}?tenantId={tenantId}", id, action, resolved)
            .retrieve()
            .body(ConsoleInstanceActionResponse.class));
  }

  @Override
  public ConsolePartitionActionResponse partitionAction(Long id, String tenantId, String action) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "partition-action",
        () -> orchestratorInternalRestClient
            .client()
            .post()
            .uri(
                "/internal/instances/partitions/{id}/{action}?tenantId={tenantId}",
                id,
                action,
                resolved)
            .retrieve()
            .body(ConsolePartitionActionResponse.class));
  }

  @Override
  public ConsoleRetryFailedPartitionsResponse retryFailedPartitions(
      Long instanceId, String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "retry-failed-partitions",
        () -> orchestratorInternalRestClient
            .client()
            .post()
            .uri(
                "/internal/instances/{id}/partitions/retry-failed?tenantId={tenantId}",
                instanceId,
                resolved)
            .retrieve()
            .body(ConsoleRetryFailedPartitionsResponse.class));
  }

  @Override
  public ConsoleWorkflowRunActionResponse workflowRunAction(
      Long id, String tenantId, String action) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    ConsoleWorkflowRunActionResponse response = downstreamFallback.callOrThrow(
        SVC,
        "workflow-run-action",
        () -> orchestratorInternalRestClient
            .client()
            .post()
            .uri("/internal/workflow-runs/{id}/{action}?tenantId={tenantId}", id, action, resolved)
            .retrieve()
            .body(ConsoleWorkflowRunActionResponse.class));
    publishRefresh(resolved);
    return response;
  }

  @Override
  public ConsoleWorkflowRunSkipNodeResponse workflowRunSkipNode(
      Long id, String tenantId, String nodeCode) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    ConsoleWorkflowRunSkipNodeResponse response = downstreamFallback.callOrThrow(
        SVC,
        "workflow-run-skip-node",
        () -> orchestratorInternalRestClient
            .client()
            .post()
            .uri(
                "/internal/workflow-runs/{id}/skip-node?tenantId={tenantId}&nodeCode={nodeCode}",
                id,
                resolved,
                nodeCode)
            .retrieve()
            .body(ConsoleWorkflowRunSkipNodeResponse.class));
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
        () -> orchestratorInternalRestClient
            .client()
            .get()
            .uri(uriBuilder -> uriBuilder
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
        () -> orchestratorInternalRestClient
            .client()
            .get()
            .uri(uriBuilder -> uriBuilder
                .path("/internal/scheduler/snapshot/history")
                .queryParam(PARAM_TENANT_ID, resolved)
                .queryParam("limit", limit)
                .build())
            .retrieve()
            .body(
                new ParameterizedTypeReference<List<ConsoleSchedulerSnapshotHistoryResponse>>() {}),
        ex -> List.of());
  }

  @Override
  public OutboxCleanupProxyResponse outboxCleanup(String tenantId, int retainDays) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "outbox-cleanup",
        () -> orchestratorInternalRestClient
            .client()
            .post()
            .uri(uriBuilder -> uriBuilder
                .path("/internal/outbox/cleanup")
                .queryParam(PARAM_TENANT_ID, resolved)
                .queryParam("retainDays", retainDays)
                .build())
            .retrieve()
            .body(OutboxCleanupProxyResponse.class));
  }

  @Override
  public OutboxRepublishProxyResponse outboxRepublish(String tenantId, List<Long> ids) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "outbox-republish",
        () -> orchestratorInternalRestClient
            .client()
            .post()
            .uri(uriBuilder -> uriBuilder
                .path("/internal/outbox/republish")
                .queryParam(PARAM_TENANT_ID, resolved)
                .build())
            .body(Map.of("ids", ids == null ? List.of() : ids))
            .retrieve()
            .body(OutboxRepublishProxyResponse.class));
  }

  @Override
  public Map<String, Integer> adminTestDataCleanupByPrefix(String prefix) {
    return downstreamFallback.callOrThrow(
        SVC,
        "admin-test-data-cleanup",
        () -> orchestratorInternalRestClient
            .client()
            .delete()
            .uri(uriBuilder -> uriBuilder
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
        () -> orchestratorInternalRestClient
            .client()
            .delete()
            .uri(uriBuilder -> uriBuilder
                .path("/internal/admin/test-data/by-ids")
                .queryParam("ids", ids)
                .build())
            .retrieve()
            .body(new ParameterizedTypeReference<Map<String, Integer>>() {}));
  }

  @Override
  public ConsoleBatchDayOperateResponse batchDayOperate(
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
    ConsoleBatchDayOperateResponse response = downstreamFallback.callOrThrow(
        SVC,
        "batch-day-operate",
        () -> orchestratorInternalRestClient
            .client()
            .post()
            .uri("/internal/batch-days/operate")
            .body(body)
            .retrieve()
            .body(ConsoleBatchDayOperateResponse.class));
    publishRefresh(resolved);
    return response;
  }

  @Override
  public ConsoleForensicExportResponse requestForensicExport(
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
        () -> orchestratorInternalRestClient
            .client()
            .post()
            .uri("/internal/forensic/export")
            .body(body)
            .retrieve()
            .body(ConsoleForensicExportResponse.class));
  }

  @Override
  public byte[] downloadForensicExport(String tenantId, String exportId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "forensic-export-download",
        () -> orchestratorInternalRestClient
            .client()
            .get()
            .uri(uriBuilder -> uriBuilder
                .path("/internal/forensic/export/{exportId}/download")
                .queryParam(PARAM_TENANT_ID, resolved)
                .build(exportId))
            .retrieve()
            .body(byte[].class));
  }

  @Override
  public void downloadForensicExport(String tenantId, String exportId, OutputStream outputStream)
      throws IOException {
    String resolved = tenantGuard.resolveTenant(tenantId);
    downstreamFallback.callOrThrow(
        SVC,
        "forensic-export-download-stream",
        () -> orchestratorInternalRestClient
            .client()
            .get()
            .uri(uriBuilder -> uriBuilder
                .path("/internal/forensic/export/{exportId}/download")
                .queryParam(PARAM_TENANT_ID, resolved)
                .build(exportId))
            .exchange((request, response) -> {
              try (InputStream input = response.getBody()) {
                if (input == null) {
                  throw new IOException("forensic export response body is empty");
                }
                input.transferTo(outputStream);
              }
              return null;
            }));
  }

  @Override
  public List<ConsolePipelineProgressItemResponse> pipelineProgress(
      String tenantId, List<String> workerCodes) {
    if (workerCodes == null || workerCodes.isEmpty()) {
      return List.of();
    }
    String resolved = tenantGuard.resolveTenant(tenantId);
    String workerCodesParam = String.join(",", workerCodes);
    return downstreamFallback.callOrFallback(
        SVC,
        "pipeline-progress",
        () -> orchestratorInternalRestClient
            .client()
            .get()
            .uri(uriBuilder -> uriBuilder
                .path("/internal/pipeline-progress")
                .queryParam(PARAM_TENANT_ID, resolved)
                .queryParam("workerCodes", workerCodesParam)
                .build())
            .retrieve()
            .body(new ParameterizedTypeReference<List<ConsolePipelineProgressItemResponse>>() {}),
        ex -> List.of());
  }

  @Override
  public List<ConsolePipelineProgressItemResponse> pipelineProgressByInstance(
      String tenantId, Long pipelineInstanceId) {
    if (EmptyChecks.isNull(pipelineInstanceId) || pipelineInstanceId <= 0) {
      return List.of();
    }
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrFallback(
        SVC,
        "pipeline-progress-by-instance",
        () -> orchestratorInternalRestClient
            .client()
            .get()
            .uri(uriBuilder -> uriBuilder
                .path("/internal/pipeline-progress/by-pipeline")
                .queryParam(PARAM_TENANT_ID, resolved)
                .queryParam("pipelineInstanceId", pipelineInstanceId)
                .build())
            .retrieve()
            .body(new ParameterizedTypeReference<List<ConsolePipelineProgressItemResponse>>() {}),
        ex -> List.of());
  }

  @Override
  public CommonResponse<ConsoleDryRunPlanResponse> dryRunPlan(DryRunPlanRequest request) {
    request.setTenantId(tenantGuard.resolveTenant(request.getTenantId()));
    return downstreamFallback.callOrThrow(
        SVC,
        "dry-run-plan",
        () -> orchestratorInternalRestClient
            .client()
            .post()
            .uri("/internal/orchestrator/dry-run/plan")
            .body(request)
            .retrieve()
            .body(new ParameterizedTypeReference<CommonResponse<ConsoleDryRunPlanResponse>>() {}));
  }

  @Override
  public CommonResponse<ConsoleBatchDayReplaySessionResponse> batchDayReplaySubmit(
      BatchDayReplaySubmitRequest request) {
    request.setTenantId(tenantGuard.resolveTenant(request.getTenantId()));
    return postReplay(
        "batch-day-replay-submit",
        "/internal/orchestrator/batch-day-replay/sessions",
        request,
        new ParameterizedTypeReference<>() {});
  }

  @Override
  public CommonResponse<ConsoleBatchDayReplayPreviewResponse> batchDayReplayPreview(
      BatchDayReplaySubmitRequest request) {
    request.setTenantId(tenantGuard.resolveTenant(request.getTenantId()));
    return postReplay(
        "batch-day-replay-preview",
        "/internal/orchestrator/batch-day-replay/sessions/preview",
        request,
        new ParameterizedTypeReference<>() {});
  }

  @Override
  public CommonResponse<List<ConsoleBatchDayReplaySessionResponse>> batchDayReplayList(
      String tenantId, String status, int limit) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "batch-day-replay-list",
        () -> orchestratorInternalRestClient
            .client()
            .get()
            .uri(uriBuilder -> {
              UriBuilder builder = uriBuilder
                  .path("/internal/orchestrator/batch-day-replay/sessions")
                  .queryParam(PARAM_TENANT_ID, resolved)
                  .queryParam("limit", limit);
              if (EmptyChecks.isNotBlank(status)) {
                builder.queryParam("status", status);
              }
              return builder.build();
            })
            .retrieve()
            .body(new ParameterizedTypeReference<>() {}));
  }

  @Override
  public CommonResponse<ConsoleBatchDayReplaySessionResponse> batchDayReplayApprove(
      Long sessionId, String tenantId, String approver) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return postReplayWithoutBody(
        "batch-day-replay-approve",
        "/internal/orchestrator/batch-day-replay/sessions/{id}/approve",
        sessionId,
        resolved,
        approver,
        true);
  }

  @Override
  public CommonResponse<ConsoleBatchDayReplaySessionResponse> batchDayReplayCancel(
      Long sessionId, String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return postReplayWithoutBody(
        "batch-day-replay-cancel",
        "/internal/orchestrator/batch-day-replay/sessions/{id}/cancel",
        sessionId,
        resolved,
        null,
        false);
  }

  @Override
  public CommonResponse<ConsoleBatchDayReplaySessionResponse> batchDayReplayDetail(
      Long sessionId, String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "batch-day-replay-detail",
        () -> orchestratorInternalRestClient
            .client()
            .get()
            .uri(
                "/internal/orchestrator/batch-day-replay/sessions/{id}?tenantId={tenantId}",
                sessionId,
                resolved)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {}));
  }

  @Override
  public CommonResponse<List<ConsoleBatchDayReplayEntryResponse>> batchDayReplayEntries(
      Long sessionId, String tenantId, String status, int limit) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "batch-day-replay-entries",
        () -> orchestratorInternalRestClient
            .client()
            .get()
            .uri(uriBuilder -> {
              UriBuilder builder = uriBuilder
                  .path("/internal/orchestrator/batch-day-replay/sessions/{id}/entries")
                  .queryParam(PARAM_TENANT_ID, resolved)
                  .queryParam("limit", limit);
              if (EmptyChecks.isNotBlank(status)) {
                builder.queryParam("status", status);
              }
              return builder.build(sessionId);
            })
            .retrieve()
            .body(new ParameterizedTypeReference<>() {}));
  }

  @Override
  public CommonResponse<List<ConsoleResultVersionResponse>> resultVersions(
      String tenantId, String businessKey, int limit) {
    return getResultVersionResponse(
        "result-versions-list",
        "/internal/orchestrator/result-versions",
        tenantId,
        businessKey,
        limit,
        new ParameterizedTypeReference<>() {});
  }

  @Override
  public CommonResponse<ConsoleResultVersionResponse> effectiveResultVersion(
      String tenantId, String businessKey) {
    return getResultVersionResponse(
        "result-version-effective",
        "/internal/orchestrator/result-versions/effective",
        tenantId,
        businessKey,
        null,
        new ParameterizedTypeReference<>() {});
  }

  @Override
  public CommonResponse<ConsoleResultVersionResponse> resultVersion(Long id, String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "result-version-detail",
        () -> orchestratorInternalRestClient
            .client()
            .get()
            .uri("/internal/orchestrator/result-versions/{id}?tenantId={tenantId}", id, resolved)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {}));
  }

  @Override
  public CommonResponse<ConsoleResultVersionResponse> promoteResultVersion(
      Long id, String tenantId) {
    return postResultVersionAction("result-version-promote", id, tenantId, "promote");
  }

  @Override
  public CommonResponse<ConsoleResultVersionResponse> rejectResultVersion(
      Long id, String tenantId) {
    return postResultVersionAction("result-version-reject", id, tenantId, "reject");
  }

  @Override
  public AssetPartitionReadinessResponse assetPartitionReadiness(
      String tenantId, String jobCode, LocalDate bizDate) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "asset-partition-readiness",
        () -> orchestratorInternalRestClient
            .client()
            .get()
            .uri(
                "/internal/readiness/job?tenantId={tenantId}&jobCode={jobCode}&bizDate={bizDate}",
                resolved,
                jobCode,
                bizDate)
            .retrieve()
            .body(AssetPartitionReadinessResponse.class));
  }

  @Override
  public CommonResponse<LineageEvidenceResponse> lineageByResultVersion(Long id, String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "lineage-result-version",
        () -> orchestratorInternalRestClient
            .client()
            .get()
            .uri(
                "/internal/orchestrator/lineage/result-versions/{id}?tenantId={tenantId}",
                id,
                resolved)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {}));
  }

  @Override
  public CommonResponse<LineageEvidenceResponse> lineageByEffective(
      String tenantId, String businessKey) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "lineage-effective",
        () -> orchestratorInternalRestClient
            .client()
            .get()
            .uri(
                "/internal/orchestrator/lineage/effective?tenantId={tenantId}&businessKey={businessKey}",
                resolved,
                businessKey)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {}));
  }

  @Override
  public CommonResponse<CapacityProfileResponse> capacityProfile(
      String tenantId, String from, String to, String groupBy, Integer limit) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "capacity-profile",
        () -> orchestratorInternalRestClient
            .client()
            .get()
            .uri(uriBuilder -> {
              UriBuilder builder = uriBuilder
                  .path("/internal/orchestrator/capacity-profile")
                  .queryParam(PARAM_TENANT_ID, resolved)
                  .queryParam("groupBy", groupBy)
                  .queryParam("limit", limit);
              if (EmptyChecks.isNotBlank(from)) {
                builder.queryParam("from", from);
              }
              if (EmptyChecks.isNotBlank(to)) {
                builder.queryParam("to", to);
              }
              return builder.build();
            })
            .retrieve()
            .body(new ParameterizedTypeReference<>() {}));
  }

  private <T> CommonResponse<T> postReplay(
      String operation,
      String path,
      Object body,
      ParameterizedTypeReference<CommonResponse<T>> responseType) {
    return downstreamFallback.callOrThrow(
        SVC,
        operation,
        () -> orchestratorInternalRestClient
            .client()
            .post()
            .uri(path)
            .body(body)
            .retrieve()
            .body(responseType));
  }

  private CommonResponse<ConsoleBatchDayReplaySessionResponse> postReplayWithoutBody(
      String operation,
      String path,
      Long sessionId,
      String tenantId,
      String approver,
      boolean includeApprover) {
    return downstreamFallback.callOrThrow(
        SVC,
        operation,
        () -> orchestratorInternalRestClient
            .client()
            .post()
            .uri(uriBuilder -> {
              UriBuilder builder = uriBuilder.path(path).queryParam(PARAM_TENANT_ID, tenantId);
              if (includeApprover) {
                builder.queryParam("approver", approver);
              }
              return builder.build(sessionId);
            })
            .retrieve()
            .body(new ParameterizedTypeReference<>() {}));
  }

  private <T> CommonResponse<T> getResultVersionResponse(
      String operation,
      String path,
      String tenantId,
      String businessKey,
      Integer limit,
      ParameterizedTypeReference<CommonResponse<T>> responseType) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        operation,
        () -> orchestratorInternalRestClient
            .client()
            .get()
            .uri(uriBuilder -> {
              UriBuilder builder = uriBuilder
                  .path(path)
                  .queryParam(PARAM_TENANT_ID, resolved)
                  .queryParam("businessKey", businessKey);
              if (EmptyChecks.isNotNull(limit)) {
                builder.queryParam("limit", limit);
              }
              return builder.build();
            })
            .retrieve()
            .body(responseType));
  }

  private CommonResponse<ConsoleResultVersionResponse> postResultVersionAction(
      String operation, Long id, String tenantId, String action) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        operation,
        () -> orchestratorInternalRestClient
            .client()
            .post()
            .uri(
                "/internal/orchestrator/result-versions/{id}/{action}?tenantId={tenantId}",
                id,
                action,
                resolved)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {}));
  }

  private void publishRefresh(String tenantId) {
    domainEventPublisher.publishChanged(tenantId, "workflow-runs", "workflow-run-updated");
    domainEventPublisher.publishChanged(tenantId, "job-instances", "job-instance-updated");
    domainEventPublisher.publishSummaryRefresh(tenantId);
  }
}
