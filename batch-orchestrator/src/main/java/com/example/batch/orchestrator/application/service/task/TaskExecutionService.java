package com.example.batch.orchestrator.application.service.task;

import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import java.time.Instant;
import java.util.List;

/**
 * 任务执行管理服务。 覆盖 JobTask 从创建、分配 Worker、租约续期、状态更新到执行日志追加的完整生命周期， 同时记录工作流节点运行（NodeRun）的就绪、启动与结束状态。
 * 实现类须保证所有状态写入由 Orchestrator 发起，Worker 不得直接修改实例状态。
 */
public interface TaskExecutionService {

  JobTaskEntity createTask(JobTaskEntity task);

  JobTaskEntity assignWorker(String tenantId, Long taskId, String workerCode);

  /**
   * 加载任务的 effective config 快照(P1-2.1)。详见 {@link TaskAssignmentService#loadEffectiveConfig(String,
   * Long)}。
   */
  EffectiveTaskConfig loadEffectiveConfig(String tenantId, Long taskId);

  boolean renewTaskLease(String tenantId, Long taskId, String workerCode);

  JobTaskEntity updateTaskStatus(
      String tenantId, Long taskId, String taskStatus, String errorCode, String errorMessage);

  JobExecutionLogEntity appendLog(JobExecutionLogEntity log);

  List<JobExecutionLogEntity> listLogs(String tenantId, Long jobInstanceId, Long jobPartitionId);

  JobTaskEntity markRunning(String tenantId, Long taskId, Instant startedAt);

  WorkflowNodeRunEntity recordNodeRunReady(Long workflowRunId, String nodeCode, String nodeType);

  WorkflowNodeRunEntity recordNodeRunStart(
      Long workflowRunId, String nodeCode, String nodeType, Instant startedAt);

  WorkflowNodeRunEntity recordNodeRunFinish(TaskOutcomeService.NodeRunFinishCommand command);

  JobTaskEntity applyTaskOutcome(TaskOutcomeCommand command);
}
