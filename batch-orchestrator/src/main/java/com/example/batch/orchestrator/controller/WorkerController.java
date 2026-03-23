package com.example.batch.orchestrator.controller;

import com.example.batch.common.dto.WorkerHeartbeatDto;
import com.example.batch.orchestrator.application.service.WorkerDrainGovernanceService;
import com.example.batch.orchestrator.controller.request.WorkerDrainRequest;
import com.example.batch.orchestrator.controller.request.WorkerTenantRequest;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.service.WorkerRegistryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/workers")
@RequiredArgsConstructor
public class WorkerController {

    private final WorkerRegistryService workerRegistryService;
    private final WorkerDrainGovernanceService workerDrainGovernanceService;

    @PostMapping("/register")
    public WorkerRegistryRecord register(@RequestBody WorkerHeartbeatDto request) {
        return workerRegistryService.register(request);
    }

    @PostMapping("/{workerCode}/heartbeat")
    public WorkerRegistryRecord heartbeat(@PathVariable String workerCode,
                                          @RequestBody(required = false) WorkerHeartbeatDto request) {
        return workerRegistryService.heartbeat(workerCode, request);
    }

    @PostMapping("/{workerCode}/deactivate")
    public void deactivate(@PathVariable String workerCode,
                           @RequestBody WorkerHeartbeatDto request) {
        workerRegistryService.deactivate(request.tenantId(), workerCode);
    }

    @PostMapping("/{workerCode}/status")
    public WorkerRegistryRecord updateStatus(@PathVariable String workerCode,
                                             @RequestBody WorkerHeartbeatDto request) {
        return workerRegistryService.updateStatus(request.tenantId(), workerCode, request.status());
    }

    @PostMapping("/{workerCode}/drain")
    public WorkerRegistryRecord drain(@PathVariable String workerCode,
                                      @RequestBody WorkerDrainRequest request) {
        return workerDrainGovernanceService.startDrain(request.tenantId(), workerCode, request.timeoutSeconds());
    }

    @PostMapping("/{workerCode}/force-offline")
    public WorkerRegistryRecord forceOffline(@PathVariable String workerCode,
                                             @RequestBody WorkerTenantRequest request) {
        return workerDrainGovernanceService.forceOffline(request.tenantId(), workerCode);
    }

    @GetMapping("/{workerCode}/claimed-tasks")
    public List<JobTaskEntity> claimedTasks(@PathVariable String workerCode,
                                            @RequestParam String tenantId) {
        return workerDrainGovernanceService.listClaimedTasks(tenantId, workerCode);
    }
}
