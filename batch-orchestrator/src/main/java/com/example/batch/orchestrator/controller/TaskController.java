package com.example.batch.orchestrator.controller;

import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.application.service.TaskExecutionService;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.dto.TaskExecutionReportDto;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskExecutionService taskExecutionService;

    @PostMapping("/{taskId}/claim")
    public void claim(@PathVariable Long taskId, @RequestBody TaskClaimRequest request) {
        JobTaskEntity task = taskExecutionService.assignWorker(request.tenantId(), taskId, request.workerId());
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found");
        }
        if (!isClaimedBy(task, request.workerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task already claimed");
        }
    }

    @PostMapping("/{taskId}/report")
    public void report(@PathVariable Long taskId, @RequestBody TaskExecutionReportDto request) {
        taskExecutionService.applyTaskOutcome(
                new TaskOutcomeCommand(
                        request.getTenantId(),
                        taskId,
                        request.isSuccess(),
                        request.getErrorCode(),
                        request.getErrorMessage()));
    }

    @PostMapping("/{taskId}/renew")
    public void renew(@PathVariable Long taskId, @RequestBody TaskClaimRequest request) {
        boolean renewed = taskExecutionService.renewTaskLease(request.tenantId(), taskId, request.workerId());
        if (!renewed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task lease renew rejected");
        }
    }

    private boolean isClaimedBy(JobTaskEntity task, String workerId) {
        return task != null
                && TaskStatus.RUNNING.code().equals(task.getTaskStatus())
                && workerId != null
                && workerId.equals(task.getAssignedWorkerCode());
    }

    public record TaskClaimRequest(String tenantId, String workerId) {
    }
}
