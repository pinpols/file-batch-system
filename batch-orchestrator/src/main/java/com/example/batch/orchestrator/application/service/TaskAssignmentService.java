package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import java.time.Instant;
import java.util.List;

/**
 * Handles worker assignment, task lease management, status updates, and log appending.
 * Extracted from {@link DefaultTaskExecutionService}.
 */
public interface TaskAssignmentService {

    JobTaskEntity assignWorker(String tenantId, Long taskId, String workerCode);

    boolean renewTaskLease(String tenantId, Long taskId, String workerCode);

    JobTaskEntity updateTaskStatus(String tenantId, Long taskId, String taskStatus, String errorCode, String errorMessage);

    JobExecutionLogEntity appendLog(JobExecutionLogEntity log);

    List<JobExecutionLogEntity> listLogs(String tenantId, Long jobInstanceId, Long jobPartitionId);

    JobTaskEntity markRunning(String tenantId, Long taskId, Instant startedAt);
}
