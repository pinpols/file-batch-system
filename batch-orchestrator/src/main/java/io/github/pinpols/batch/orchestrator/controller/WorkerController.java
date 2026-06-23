package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.common.dto.WorkerHeartbeatDto;
import io.github.pinpols.batch.common.dto.WorkerHeartbeatResponse;
import io.github.pinpols.batch.orchestrator.application.ratelimit.RateLimitAction;
import io.github.pinpols.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import io.github.pinpols.batch.orchestrator.application.service.governance.WorkerDrainGovernanceService;
import io.github.pinpols.batch.orchestrator.controller.request.WorkerDrainRequest;
import io.github.pinpols.batch.orchestrator.controller.request.WorkerTenantRequest;
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
  public WorkerRegistryEntity register(
      @RequestBody WorkerHeartbeatDto request, HttpServletRequest httpRequest) {
    WorkerHeartbeatDto normalized = normalize(request, httpRequest);
    // 缺口①:per-tenant worker 注册限流(opt-in,默认 max=0/disabled 直接放行)。
    // 仅 register 挂硬限流防注册风暴;claim/report/heartbeat 高频热路径不挂,避免误伤正常 worker。
    String tenantId = normalized == null ? null : normalized.tenantId();
    if (!tenantActionRateLimiter.tryConsume(tenantId, RateLimitAction.WORKER_REGISTER)) {
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "worker register rate limit exceeded");
    }
    return workerRegistryService.register(normalized);
  }

  // SDK Phase 2 §2.3:心跳回包从 WorkerRegistryEntity 改为下发 platform directive。
  // 老 worker 忽略响应体(worker-core toBodilessEntity / SDK 旧版不消费),切换无 break。
  @PostMapping("/{workerCode}/heartbeat")
  public WorkerHeartbeatResponse heartbeat(
      @PathVariable String workerCode,
      @RequestBody(required = false) WorkerHeartbeatDto request,
      HttpServletRequest httpRequest) {
    WorkerRegistryEntity worker =
        workerRegistryService.heartbeat(workerCode, normalize(request, httpRequest));
    return WorkerHeartbeatResponse.fromWorkerState(worker.status(), worker.maxConcurrent());
  }

  @PostMapping("/{workerCode}/deactivate")
  public void deactivate(
      @PathVariable String workerCode,
      @RequestBody WorkerHeartbeatDto request,
      HttpServletRequest httpRequest) {
    workerRegistryService.deactivate(resolveTenant(request, httpRequest), workerCode);
  }

  @PostMapping("/{workerCode}/status")
  public WorkerRegistryEntity updateStatus(
      @PathVariable String workerCode,
      @RequestBody WorkerHeartbeatDto request,
      HttpServletRequest httpRequest) {
    return workerRegistryService.updateStatus(
        resolveTenant(request, httpRequest), workerCode, request.status());
  }

  @PostMapping("/{workerCode}/drain")
  public WorkerRegistryEntity drain(
      @PathVariable String workerCode,
      @RequestBody WorkerDrainRequest request,
      HttpServletRequest httpRequest) {
    String tenantId =
        InternalRequestTenantGuard.resolveTenant(
            httpRequest, request == null ? null : request.tenantId());
    return workerDrainGovernanceService.startDrain(
        tenantId, workerCode, request == null ? null : request.timeoutSeconds());
  }

  @PostMapping("/{workerCode}/force-offline")
  public WorkerRegistryEntity forceOffline(
      @PathVariable String workerCode,
      @RequestBody WorkerTenantRequest request,
      HttpServletRequest httpRequest) {
    return workerDrainGovernanceService.forceOffline(
        resolveTenant(request, httpRequest), workerCode);
  }

  @PostMapping("/{workerCode}/takeover")
  public WorkerRegistryEntity takeover(
      @PathVariable String workerCode,
      @RequestBody WorkerTenantRequest request,
      HttpServletRequest httpRequest) {
    return workerDrainGovernanceService.takeover(resolveTenant(request, httpRequest), workerCode);
  }

  @PostMapping("/{workerCode}/warmup")
  public WorkerRegistryEntity warmup(
      @PathVariable String workerCode,
      @RequestBody WorkerTenantRequest request,
      HttpServletRequest httpRequest) {
    return workerDrainGovernanceService.warmup(resolveTenant(request, httpRequest), workerCode);
  }

  @GetMapping("/{workerCode}/claimed-tasks")
  public List<JobTaskEntity> claimedTasks(
      @PathVariable String workerCode,
      @RequestParam String tenantId,
      HttpServletRequest httpRequest) {
    return workerDrainGovernanceService.listClaimedTasks(
        InternalRequestTenantGuard.resolveTenant(httpRequest, tenantId), workerCode);
  }

  private static WorkerHeartbeatDto normalize(
      WorkerHeartbeatDto request, HttpServletRequest httpRequest) {
    if (request == null) {
      String tenantId = InternalRequestTenantGuard.resolveTenant(httpRequest, null);
      return tenantId == null
          ? null
          : new WorkerHeartbeatDto(
              tenantId, null, null, null, null, null, null, null, null, null, null, null, null,
              null, null, null);
    }
    String tenantId = resolveTenant(request, httpRequest);
    return new WorkerHeartbeatDto(
        tenantId,
        request.workerCode(),
        request.workerGroup(),
        request.status(),
        request.hostName(),
        request.hostIp(),
        request.processId(),
        request.buildId(),
        request.sdkVersion(),
        request.heartbeatAt(),
        request.capabilityTags(),
        request.currentLoad(),
        request.taskTypes(),
        request.rowsProcessed(),
        request.totalRowsHint(),
        request.protocolVersion());
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
