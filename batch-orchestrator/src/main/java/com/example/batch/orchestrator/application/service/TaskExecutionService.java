package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import java.time.Instant;
import java.util.List;

public interface TaskExecutionService {

    JobTaskEntity createTask(JobTaskEntity task);

    JobTaskEntity assignWorker(String tenantId, Long taskId, String workerCode);

    boolean renewTaskLease(String tenantId, Long taskId, String workerCode);

    JobTaskEntity updateTaskStatus(String tenantId, Long taskId, String taskStatus, String errorCode, String errorMessage);

    JobExecutionLogEntity appendLog(JobExecutionLogEntity log);

    List<JobExecutionLogEntity> listLogs(String tenantId, Long jobInstanceId, Long jobPartitionId);

    JobTaskEntity markRunning(String tenantId, Long taskId, Instant startedAt);

    WorkflowNodeRunEntity recordNodeRunReady(Long workflowRunId, String nodeCode, String nodeType);

    WorkflowNodeRunEntity recordNodeRunStart(Long workflowRunId, String nodeCode, String nodeType, Instant startedAt);

    WorkflowNodeRunEntity recordNodeRunFinish(TaskOutcomeService.NodeRunFinishCommand command);

    JobTaskEntity applyTaskOutcome(TaskOutcomeCommand command);
}
