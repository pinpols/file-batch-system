package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.service.TaskControllerApplicationService;
import com.example.batch.orchestrator.controller.request.TaskExecutionReportDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskControllerApplicationService taskControllerApplicationService;

    @PostMapping("/{taskId}/claim")
    public void claim(@PathVariable Long taskId, @RequestBody TaskClaimRequest request) {
        taskControllerApplicationService.claim(taskId, request);
    }

    @PostMapping("/{taskId}/report")
    public void report(@PathVariable Long taskId, @RequestBody TaskExecutionReportDto request) {
        taskControllerApplicationService.report(taskId, request);
    }

    @PostMapping("/{taskId}/renew")
    public void renew(@PathVariable Long taskId, @RequestBody TaskClaimRequest request) {
        taskControllerApplicationService.renew(taskId, request);
    }

    public record TaskClaimRequest(String tenantId, String workerId) {
    }
}
