package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.orchestrator.controller.TaskController.TaskClaimRequest;
import com.example.batch.orchestrator.controller.request.TaskExecutionReportDto;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class TaskControllerApplicationService {

    private final TaskExecutionService taskExecutionService;

    public void claim(Long taskId, TaskClaimRequest request) {
        JobTaskEntity task = taskExecutionService.assignWorker(request.tenantId(), taskId, request.workerId());
        if (task == null) {
            throw new BizException(ResultCode.NOT_FOUND, "task not found");
        }
        if (!isClaimedBy(task, request.workerId())) {
            throw new BizException(ResultCode.CONFLICT, "task already claimed");
        }
    }

    public void report(Long taskId, TaskExecutionReportDto request) {
        String errorCode = resolveFailureField(request.getErrorCode(), request.getCode(), request.isSuccess());
        String errorMessage = resolveFailureField(request.getErrorMessage(), request.getMessage(), request.isSuccess());
        taskExecutionService.applyTaskOutcome(new TaskOutcomeCommand(
                request.getTenantId(),
                taskId,
                request.isSuccess(),
                request.getResultSummary(),
                errorCode,
                errorMessage
        ));
    }

    public void renew(Long taskId, TaskClaimRequest request) {
        boolean renewed = taskExecutionService.renewTaskLease(request.tenantId(), taskId, request.workerId());
        if (!renewed) {
            throw new BizException(ResultCode.CONFLICT, "task lease renew rejected");
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
        return "UNKNOWN";
    }
}
