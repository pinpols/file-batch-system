package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.service.InstanceManagementApplicationService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/internal/instances")
@RequiredArgsConstructor
public class InstanceManagementController {

    private final InstanceManagementApplicationService instanceManagementApplicationService;

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(
            @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
        return instanceManagementApplicationService.cancel(tenantId, id);
    }

    @PostMapping("/{id}/terminate")
    public Map<String, Object> terminate(
            @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
        return instanceManagementApplicationService.terminate(tenantId, id);
    }

    @PostMapping("/partitions/{id}/cancel")
    public Map<String, Object> cancelPartition(
            @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
        return instanceManagementApplicationService.cancelPartition(tenantId, id);
    }

    @PostMapping("/partitions/{id}/retry")
    public Map<String, Object> retryPartition(
            @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
        return instanceManagementApplicationService.retryPartition(tenantId, id);
    }
}
