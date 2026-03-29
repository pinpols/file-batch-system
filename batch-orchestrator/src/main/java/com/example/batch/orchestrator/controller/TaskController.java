package com.example.batch.orchestrator.controller;

import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.application.service.TaskExecutionService;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.controller.request.TaskExecutionReportDto;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
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
        String errorCode = resolveFailureField(request.getErrorCode(), request.getCode(), request.isSuccess());
        String errorMessage = resolveFailureField(request.getErrorMessage(), request.getMessage(), request.isSuccess());
        taskExecutionService.applyTaskOutcome(
                new TaskOutcomeCommand(
                        request.getTenantId(),
                        taskId,
                        request.isSuccess(),
                        request.getResultSummary(),
                        errorCode,
                        errorMessage));
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

    private String resolveFailureField(String primary, String fallback, boolean success) {
        if (success) {
            return null;
        }
        if (StringUtils.hasText(primary)) {
            return primary;
        }
        if (StringUtils.hasText(fallback)) {
            return fallback;
        }
        // Worker 或序列化路径可能未带 code/errorCode；失败态仍应落库可观测的错误码
        return "UNKNOWN";
    }

    public record TaskClaimRequest(String tenantId, String workerId) {
    }
}
