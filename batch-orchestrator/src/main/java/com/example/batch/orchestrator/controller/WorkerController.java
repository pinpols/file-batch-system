package com.example.batch.orchestrator.controller;

import com.example.batch.common.dto.WorkerHeartbeatDto;
import com.example.batch.orchestrator.application.service.WorkerDrainGovernanceService;
import com.example.batch.orchestrator.controller.request.WorkerDrainRequest;
import com.example.batch.orchestrator.controller.request.WorkerTenantRequest;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import com.example.batch.orchestrator.service.WorkerRegistryServerService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

  @PostMapping("/register")
  public WorkerRegistryEntity register(@RequestBody WorkerHeartbeatDto request) {
    return workerRegistryService.register(request);
  }

  @PostMapping("/{workerCode}/heartbeat")
  public WorkerRegistryEntity heartbeat(
      @PathVariable String workerCode, @RequestBody(required = false) WorkerHeartbeatDto request) {
    return workerRegistryService.heartbeat(workerCode, request);
  }

  @PostMapping("/{workerCode}/deactivate")
  public void deactivate(@PathVariable String workerCode, @RequestBody WorkerHeartbeatDto request) {
    workerRegistryService.deactivate(request.tenantId(), workerCode);
  }

  @PostMapping("/{workerCode}/status")
  public WorkerRegistryEntity updateStatus(
      @PathVariable String workerCode, @RequestBody WorkerHeartbeatDto request) {
    return workerRegistryService.updateStatus(request.tenantId(), workerCode, request.status());
  }

  @PostMapping("/{workerCode}/drain")
  public WorkerRegistryEntity drain(
      @PathVariable String workerCode, @RequestBody WorkerDrainRequest request) {
    return workerDrainGovernanceService.startDrain(
        request.tenantId(), workerCode, request.timeoutSeconds());
  }

  @PostMapping("/{workerCode}/force-offline")
  public WorkerRegistryEntity forceOffline(
      @PathVariable String workerCode, @RequestBody WorkerTenantRequest request) {
    return workerDrainGovernanceService.forceOffline(request.tenantId(), workerCode);
  }

  @PostMapping("/{workerCode}/takeover")
  public WorkerRegistryEntity takeover(
      @PathVariable String workerCode, @RequestBody WorkerTenantRequest request) {
    return workerDrainGovernanceService.takeover(request.tenantId(), workerCode);
  }

  @GetMapping("/{workerCode}/claimed-tasks")
  public List<JobTaskEntity> claimedTasks(
      @PathVariable String workerCode, @RequestParam String tenantId) {
    return workerDrainGovernanceService.listClaimedTasks(tenantId, workerCode);
  }
}
