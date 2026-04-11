package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.service.WorkflowRunManagementApplicationService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/internal/workflow-runs")
@RequiredArgsConstructor
public class WorkflowRunManagementController {

    private final WorkflowRunManagementApplicationService workflowRunManagementApplicationService;

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(
            @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
        return workflowRunManagementApplicationService.cancel(tenantId, id);
    }

    @PostMapping("/{id}/terminate")
    public Map<String, Object> terminate(
            @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
        return workflowRunManagementApplicationService.terminate(tenantId, id);
    }

    @PostMapping("/{id}/skip-node")
    public Map<String, Object> skipNode(
            @PathVariable Long id,
            @RequestParam("tenantId") String tenantId,
            @RequestParam("nodeCode") String nodeCode) {
        return workflowRunManagementApplicationService.skipNode(tenantId, id, nodeCode);
    }
}
