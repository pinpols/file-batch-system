package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import java.time.Instant;
import java.util.List;

/**
 * 任务执行管理服务。 覆盖 JobTask 从创建、分配 Worker、租约续期、状态更新到执行日志追加的完整生命周期， 同时记录工作流节点运行（NodeRun）的就绪、启动与结束状态。
 * 实现类须保证所有状态写入由 Orchestrator 发起，Worker 不得直接修改实例状态。
 */
public interface TaskExecutionService {

  JobTaskEntity createTask(JobTaskEntity task);

  /**
   * PERF(5.1): launch fan-out 批量创建，语义等价逐条 {@link #createTask}。详见 {@link
   * TaskCreationService#createTasks}。
   */
  List<JobTaskEntity> createTasks(List<JobTaskEntity> tasks);

  JobTaskEntity assignWorker(String tenantId, Long taskId, String workerCode);

  /**
   * 加载任务的 effective config 快照(P1-2.1)。详见 {@link TaskAssignmentService#loadEffectiveConfig(String,
   * Long)}。
   */
  EffectiveTaskConfig loadEffectiveConfig(String tenantId, Long taskId);

  /**
   * PERF(5.2b): 复用已加载 task 实体的重载，见 {@link TaskAssignmentService#loadEffectiveConfig(String,
   * JobTaskEntity)}。
   */
  EffectiveTaskConfig loadEffectiveConfig(String tenantId, JobTaskEntity task);

  /**
   * PERF(5.2c): 带请求级 worker 解析缓存的认领重载，见 {@link TaskAssignmentService#assignWorker(String, Long,
   * String, TaskAssignmentService.WorkerLookupMemo)}。
   */
  JobTaskEntity assignWorker(
      String tenantId,
      Long taskId,
      String workerCode,
      TaskAssignmentService.WorkerLookupMemo workerMemo);

  boolean renewTaskLease(
      String tenantId, Long taskId, String workerCode, String partitionInvocationId);

  /** ORCH-P4-1：心跳 = 续租 + 进度上报 + 取消感知。详见 {@link TaskAssignmentService#recordHeartbeat}。 */
  TaskAssignmentService.TaskHeartbeatResult recordHeartbeat(
      String tenantId,
      Long taskId,
      String workerCode,
      String partitionInvocationId,
      String detailsJson);

  /** PERF(5.3): 批量续租（set-based）。详见 {@link TaskAssignmentService#renewLeaseBatch}。 */
  List<TaskAssignmentService.TaskHeartbeatResult> renewLeaseBatch(
      List<TaskAssignmentService.LeaseRenewCommand> items);

  /** ORCH-P4-1：平台请求取消 RUNNING task。详见 {@link TaskAssignmentService#requestCancel}。 */
  boolean requestCancel(String tenantId, Long taskId);

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
