package com.example.batch.orchestrator.controller;

import com.example.batch.common.dto.WorkerHeartbeatDto;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.service.WorkerRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/workers")
@RequiredArgsConstructor
public class WorkerController {

    private final WorkerRegistryService workerRegistryService;

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
}
