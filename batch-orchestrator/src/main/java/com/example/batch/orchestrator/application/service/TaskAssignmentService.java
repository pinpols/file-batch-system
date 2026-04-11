package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;

import java.time.Instant;
import java.util.List;

/** 处理 Worker 分配、任务租约管理、状态更新及日志追加，从 {@link DefaultTaskExecutionService} 中拆分。 */
public interface TaskAssignmentService {

    JobTaskEntity assignWorker(String tenantId, Long taskId, String workerCode);

    boolean renewTaskLease(String tenantId, Long taskId, String workerCode);

    JobTaskEntity updateTaskStatus(
            String tenantId, Long taskId, String taskStatus, String errorCode, String errorMessage);

    JobExecutionLogEntity appendLog(JobExecutionLogEntity log);

    List<JobExecutionLogEntity> listLogs(String tenantId, Long jobInstanceId, Long jobPartitionId);

    JobTaskEntity markRunning(String tenantId, Long taskId, Instant startedAt);
}
