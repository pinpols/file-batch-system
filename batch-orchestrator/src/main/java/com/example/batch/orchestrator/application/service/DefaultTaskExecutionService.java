package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Thin facade that delegates to the three focused sub-services.
 * Preserved so all existing callers (controllers, consumers, tests) that depend on
 * {@link TaskExecutionService} continue to work without modification.
 */
@Service
@RequiredArgsConstructor
public class DefaultTaskExecutionService implements TaskExecutionService {

    private final TaskCreationService taskCreationService;
    private final TaskAssignmentService taskAssignmentService;
    private final TaskOutcomeService taskOutcomeService;

    @Override
    public JobTaskEntity createTask(JobTaskEntity task) {
        return taskCreationService.createTask(task);
    }

    @Override
    public JobTaskEntity assignWorker(String tenantId, Long taskId, String workerCode) {
        return taskAssignmentService.assignWorker(tenantId, taskId, workerCode);
    }

    @Override
    public boolean renewTaskLease(String tenantId, Long taskId, String workerCode) {
        return taskAssignmentService.renewTaskLease(tenantId, taskId, workerCode);
    }

    @Override
    public JobTaskEntity updateTaskStatus(String tenantId, Long taskId, String taskStatus,
                                          String errorCode, String errorMessage) {
        return taskAssignmentService.updateTaskStatus(tenantId, taskId, taskStatus, errorCode, errorMessage);
    }

    @Override
    public JobExecutionLogEntity appendLog(JobExecutionLogEntity log) {
        return taskAssignmentService.appendLog(log);
    }

    @Override
    public List<JobExecutionLogEntity> listLogs(String tenantId, Long jobInstanceId, Long jobPartitionId) {
        return taskAssignmentService.listLogs(tenantId, jobInstanceId, jobPartitionId);
    }

    @Override
    public JobTaskEntity markRunning(String tenantId, Long taskId, Instant startedAt) {
        return taskAssignmentService.markRunning(tenantId, taskId, startedAt);
    }

    @Override
    public WorkflowNodeRunEntity recordNodeRunReady(Long workflowRunId, String nodeCode, String nodeType) {
        return taskOutcomeService.recordNodeRunReady(workflowRunId, nodeCode, nodeType);
    }

    @Override
    public WorkflowNodeRunEntity recordNodeRunStart(Long workflowRunId, String nodeCode,
                                                    String nodeType, Instant startedAt) {
        return taskOutcomeService.recordNodeRunStart(workflowRunId, nodeCode, nodeType, startedAt);
    }

    @Override
    public WorkflowNodeRunEntity recordNodeRunFinish(Long workflowRunId, String nodeCode, String nodeType,
                                                     boolean success, String errorCode, String errorMessage,
                                                     Instant startedAt, Instant finishedAt) {
        return taskOutcomeService.recordNodeRunFinish(workflowRunId, nodeCode, nodeType,
                success, errorCode, errorMessage, startedAt, finishedAt);
    }

    @Override
    public JobTaskEntity applyTaskOutcome(TaskOutcomeCommand command) {
        return taskOutcomeService.applyTaskOutcome(command);
    }
}
