package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * TaskExecutionService 的门面实现（Facade）。
 *
 * <p>该类本身不承载复杂业务逻辑，而是把“任务创建/认领/结果回报”等能力拆分到三个更聚焦的子服务中， 以降低单类复杂度，并保持 controller/测试代码的调用入口不变：
 *
 * <ul>
 *   <li>{@link TaskCreationService}：创建任务 + 初始化 step 镜像
 *   <li>{@link TaskAssignmentService}：worker claim/renew 等租约治理
 *   <li>{@link TaskOutcomeService}：worker report → 状态机推进 / retry / DAG 推进
 * </ul>
 *
 * <p>你在排查主链路时，最关键的方法通常是 {@link #applyTaskOutcome(TaskOutcomeCommand)}（worker 回报入口）。
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
    public JobTaskEntity updateTaskStatus(
            String tenantId,
            Long taskId,
            String taskStatus,
            String errorCode,
            String errorMessage) {
        return taskAssignmentService.updateTaskStatus(
                tenantId, taskId, taskStatus, errorCode, errorMessage);
    }

    @Override
    public JobExecutionLogEntity appendLog(JobExecutionLogEntity log) {
        return taskAssignmentService.appendLog(log);
    }

    @Override
    public List<JobExecutionLogEntity> listLogs(
            String tenantId, Long jobInstanceId, Long jobPartitionId) {
        return taskAssignmentService.listLogs(tenantId, jobInstanceId, jobPartitionId);
    }

    @Override
    public JobTaskEntity markRunning(String tenantId, Long taskId, Instant startedAt) {
        return taskAssignmentService.markRunning(tenantId, taskId, startedAt);
    }

    @Override
    public WorkflowNodeRunEntity recordNodeRunReady(
            Long workflowRunId, String nodeCode, String nodeType) {
        return taskOutcomeService.recordNodeRunReady(workflowRunId, nodeCode, nodeType);
    }

    @Override
    public WorkflowNodeRunEntity recordNodeRunStart(
            Long workflowRunId, String nodeCode, String nodeType, Instant startedAt) {
        return taskOutcomeService.recordNodeRunStart(workflowRunId, nodeCode, nodeType, startedAt);
    }

    @Override
    public WorkflowNodeRunEntity recordNodeRunFinish(
            TaskOutcomeService.NodeRunFinishCommand command) {
        return taskOutcomeService.recordNodeRunFinish(command);
    }

    @Override
    public JobTaskEntity applyTaskOutcome(TaskOutcomeCommand command) {
        return taskOutcomeService.applyTaskOutcome(command);
    }
}
