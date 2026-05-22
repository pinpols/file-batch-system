package com.example.batch.console.infrastructure.ops;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.console.application.ops.ConsoleWorkerApplicationService;
import com.example.batch.console.infrastructure.query.ConsoleJobOpsSupport;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.web.ConsoleRequestMetadata;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.ops.DrainWorkerRequest;
import com.example.batch.console.web.request.ops.ForceOfflineWorkerRequest;
import com.example.batch.console.web.response.ops.ConsoleWorkerClaimedTaskResponse;
import com.example.batch.console.web.response.ops.ConsoleWorkerRegistryResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Worker 运维 BFF：把 console 的 worker 操作 HTTP 请求转发到 orchestrator {@code
 * /internal/workers/**}，并在操作成功后广播实时事件让前端刷新。
 *
 * <p>提供 4 个写操作 + 1 个查询：
 *
 * <ul>
 *   <li>{@code drain} — 优雅下线（允许 timeoutSeconds 覆盖默认超时）
 *   <li>{@code forceOffline} — 强制下线（立即接管任务）
 *   <li>{@code takeover} — 手动接管任务后标 DECOMMISSIONED
 *   <li>{@code warmup} — 预热后重新上线
 *   <li>{@code claimedTasks} — 查询 worker 当前 claim 的活跃任务列表
 * </ul>
 *
 * <p>写操作统一广播 {@code publishChanged(workers)} + {@code publishSummaryRefresh}， 让前端 worker
 * 列表与仪表盘实时刷新。
 *
 * <p>所有下游请求都带 {@code Idempotency-Key / Request-Id / Trace-Id} 三件套 （与 {@link ConsoleJobOpsSupport}
 * 保持一致的 BFF 调用协议）。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleWorkerApplicationService implements ConsoleWorkerApplicationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_WORKERS = "workers";
  private static final String KEY_TENANT_ID = "tenantId";

  private final OrchestratorInternalRestClient orchestratorInternalRestClient;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRealtimeDomainEventPublisher domainEventPublisher;

  @Override
  public ConsoleWorkerRegistryResponse drain(
      String workerCode, DrainWorkerRequest request, String idempotencyKey) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    ConsoleRequestMetadata meta = requestMetadataResolver.current();
    RestClient client = orchestratorInternalRestClient.build();
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
    RestClient client = orchestratorInternalRestClient.build();
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
    RestClient client = orchestratorInternalRestClient.build();
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
    RestClient client = orchestratorInternalRestClient.build();
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
    RestClient client = orchestratorInternalRestClient.build();
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
}
