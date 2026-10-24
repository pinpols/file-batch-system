package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.common.dto.WorkerHeartbeatDto;
import io.github.pinpols.batch.common.dto.WorkerHeartbeatResponse;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.ratelimit.RateLimitAction;
import io.github.pinpols.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import io.github.pinpols.batch.orchestrator.application.service.governance.WorkerDrainGovernanceService;
import io.github.pinpols.batch.orchestrator.controller.request.WorkerDrainRequest;
import io.github.pinpols.batch.orchestrator.controller.request.WorkerTenantRequest;
import io.github.pinpols.batch.orchestrator.controller.response.JobTaskResponse;
import io.github.pinpols.batch.orchestrator.controller.response.WorkerRegistryResponse;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.service.WorkerRegistryServerService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Worker 节点注册与生命周期管控内部控制器，基础路径 {@code /internal/workers}。 覆盖注册（{@code register}）、心跳上报（{@code
 * heartbeat}）、下线（{@code deactivate}）、 状态更新（{@code status}）、优雅排空（{@code drain}）、强制下线（{@code
 * force-offline}）、 接管（{@code takeover}）及已认领任务查询（{@code claimed-tasks}）等完整生命周期端点。 仅限 Worker
 * 节点与内部运维系统调用，不对外暴露。
 */
@RestController
@RequestMapping("/internal/workers")
@RequiredArgsConstructor
public class WorkerController {

  private final WorkerRegistryServerService workerRegistryService;
  private final WorkerDrainGovernanceService workerDrainGovernanceService;
  private final TenantActionRateLimiter tenantActionRateLimiter;

  @PostMapping("/register")
  public WorkerRegistryResponse register(
      @RequestBody WorkerHeartbeatDto request, HttpServletRequest httpRequest) {
    WorkerHeartbeatDto normalized = normalize(request, httpRequest);
    // 缺口①:per-tenant worker 注册限流(opt-in,默认 max=0/disabled 直接放行)。
    // 仅 register 挂硬限流防注册风暴;claim/report/heartbeat 高频热路径不挂,避免误伤正常 worker。
    String tenantId = normalized == null ? null : normalized.tenantId();
    if (!tenantActionRateLimiter.tryConsume(tenantId, RateLimitAction.WORKER_REGISTER)) {
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "worker register rate limit exceeded");
    }
    WorkerRegistryEntity registeredWorker = workerRegistryService.register(normalized);
    return WorkerRegistryResponse.from(registeredWorker);
  }

  // SDK Phase 2 §2.3:心跳回包从 WorkerRegistryEntity 改为下发 platform directive。
  // 老 worker 忽略响应体(worker-core toBodilessEntity / SDK 旧版不消费),切换无 break。
  @PostMapping("/{workerCode}/heartbeat")
  public WorkerHeartbeatResponse heartbeat(
      @PathVariable String workerCode,
      @RequestBody(required = false) WorkerHeartbeatDto request,
      HttpServletRequest httpRequest) {
    WorkerHeartbeatDto normalizedRequest = normalize(request, httpRequest);
    WorkerRegistryEntity worker = workerRegistryService.heartbeat(workerCode, normalizedRequest);
    return WorkerHeartbeatResponse.fromWorkerState(worker.status(), worker.maxConcurrent());
  }

  @PostMapping("/{workerCode}/deactivate")
  public void deactivate(
      @PathVariable String workerCode,
      @RequestBody WorkerHeartbeatDto request,
      HttpServletRequest httpRequest) {
    String tenantId = resolveTenant(request, httpRequest);
    workerRegistryService.deactivate(tenantId, workerCode);
  }

  @PostMapping("/{workerCode}/status")
  public WorkerRegistryResponse updateStatus(
      @PathVariable String workerCode,
      @RequestBody WorkerHeartbeatDto request,
      HttpServletRequest httpRequest) {
    String tenantId = resolveTenant(request, httpRequest);
    String requestedStatus = request.status();
    WorkerRegistryEntity worker =
        workerRegistryService.updateStatus(tenantId, workerCode, requestedStatus);
    return WorkerRegistryResponse.from(worker);
  }

  @PostMapping("/{workerCode}/drain")
  public WorkerRegistryResponse drain(
      @PathVariable String workerCode,
      @RequestBody WorkerDrainRequest request,
      HttpServletRequest httpRequest) {
    String requestedTenantId = EmptyChecks.isNull(request) ? null : request.tenantId();
    String tenantId = InternalRequestTenantGuard.resolveTenant(httpRequest, requestedTenantId);
    Integer requestedTimeoutSeconds = EmptyChecks.isNull(request) ? null : request.timeoutSeconds();
    WorkerRegistryEntity worker =
        workerDrainGovernanceService.startDrain(tenantId, workerCode, requestedTimeoutSeconds);
    return WorkerRegistryResponse.from(worker);
  }

  @PostMapping("/{workerCode}/force-offline")
  public WorkerRegistryResponse forceOffline(
      @PathVariable String workerCode,
      @RequestBody WorkerTenantRequest request,
      HttpServletRequest httpRequest) {
    String tenantId = resolveTenant(request, httpRequest);
    WorkerRegistryEntity worker =
        workerDrainGovernanceService.forceOffline(tenantId, workerCode);
    return WorkerRegistryResponse.from(worker);
  }

  @PostMapping("/{workerCode}/takeover")
  public WorkerRegistryResponse takeover(
      @PathVariable String workerCode,
      @RequestBody WorkerTenantRequest request,
      HttpServletRequest httpRequest) {
    String tenantId = resolveTenant(request, httpRequest);
    WorkerRegistryEntity worker =
        workerDrainGovernanceService.takeover(tenantId, workerCode);
    return WorkerRegistryResponse.from(worker);
  }

  @PostMapping("/{workerCode}/warmup")
  public WorkerRegistryResponse warmup(
      @PathVariable String workerCode,
      @RequestBody WorkerTenantRequest request,
      HttpServletRequest httpRequest) {
    String tenantId = resolveTenant(request, httpRequest);
    WorkerRegistryEntity worker =
        workerDrainGovernanceService.warmup(tenantId, workerCode);
    return WorkerRegistryResponse.from(worker);
  }

  @GetMapping("/{workerCode}/claimed-tasks")
  public List<JobTaskResponse> claimedTasks(
      @PathVariable String workerCode,
      @RequestParam String tenantId,
      HttpServletRequest httpRequest) {
    String resolvedTenantId = InternalRequestTenantGuard.resolveTenant(httpRequest, tenantId);
    List<JobTaskEntity> tasks =
        workerDrainGovernanceService.listClaimedTasks(resolvedTenantId, workerCode);
    return tasks.stream().map(JobTaskResponse::from).toList();
  }

  private static WorkerHeartbeatDto normalize(
      WorkerHeartbeatDto request, HttpServletRequest httpRequest) {
    if (request == null) {
      String tenantId = InternalRequestTenantGuard.resolveTenant(httpRequest, null);
      return tenantId == null
          ? null
          : WorkerHeartbeatDto.builder().tenantId(tenantId).build();
    }
    String tenantId = resolveTenant(request, httpRequest);
    return request.toBuilder().tenantId(tenantId).build();
  }

  private static String resolveTenant(WorkerHeartbeatDto request, HttpServletRequest httpRequest) {
    return InternalRequestTenantGuard.resolveTenant(
        httpRequest, request == null ? null : request.tenantId());
  }

  private static String resolveTenant(WorkerTenantRequest request, HttpServletRequest httpRequest) {
    return InternalRequestTenantGuard.resolveTenant(
        httpRequest, request == null ? null : request.tenantId());
  }
}
