package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.orchestrator.domain.context.TaskOutcomePersistenceContext;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.param.AssignWorkerParam;
import io.github.pinpols.batch.orchestrator.domain.param.ExternalOutcomeResolutionConsumeParam;
import io.github.pinpols.batch.orchestrator.domain.param.FinishTaskParam;
import io.github.pinpols.batch.orchestrator.domain.param.JobTaskPromoteStatusParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateTaskStatusParam;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskActiveByAssignedWorkerQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.domain.query.NodePartitionAssignment;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobTaskMapper {

  int insert(JobTaskEntity entity);

  /** PERF(5.1): launch fan-out 多行 INSERT；PG getGeneratedKeys 按序回填每个元素的 {@code id}。 */
  int insertBatch(List<JobTaskEntity> entities);

  JobTaskEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  /** CLAIM 读取必须同时满足父 Job 和关联 Workflow 的执行准入，包含同 Worker 的重试响应。 */
  JobTaskEntity selectForWorkerClaim(@Param("tenantId") String tenantId, @Param("id") Long id);

  /** REPORT 热路径一次读取 task 与所属 partition，避免同事务连续两次主键查询。 */
  TaskOutcomePersistenceContext selectOutcomePersistenceContext(
      @Param("tenantId") String tenantId, @Param("id") Long id);

  /** 外部工作开始前，为当前任务所有者持久化非幂等执行围栏。 */
  int markNonIdempotentExecutionStarted(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("workerCode") String workerCode,
      @Param("partitionInvocationId") String partitionInvocationId);

  JobTaskEntity selectByPartitionAndSeq(
      @Param("tenantId") String tenantId,
      @Param("jobPartitionId") Long jobPartitionId,
      @Param("taskSeq") Integer taskSeq);

  List<JobTaskEntity> selectByQuery(JobTaskQuery query);

  /** 按不可变来源 JobInstance 快速检查重放安全，不加载任务载荷。 */
  boolean existsUnknownExternalOutcomeForJobInstance(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  /** 请求以 Job 和业务日定位时，快速检查批量重放安全。 */
  boolean existsUnknownExternalOutcomeForJobBizDate(
      @Param("tenantId") String tenantId,
      @Param("jobCode") String jobCode,
      @Param("bizDate") LocalDate bizDate);

  /**
   * perf: 仅取 (job_partition_id, workflowNodeCode) 轻量投影,给 report 链路按 DAG 节点做分区计数用。
   *
   * <p>替代 {@link #selectByQuery(JobTaskQuery)} 的 {@code select *} 全量拉行(含 task_payload /
   * effective_parameters 两个大 JSON 列),node_code 由 SQL 侧 {@code task_payload ->> 'workflowNodeCode'}
   * 抽取。语义与原 Java 侧 {@code payloadStringValue(taskPayload, "workflowNodeCode")} 对齐。
   */
  List<NodePartitionAssignment> selectNodeAssignmentsByInstance(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  List<JobTaskEntity> selectReadyTasks(
      @Param("tenantId") String tenantId,
      @Param("batchSize") int batchSize,
      @Param("readyStatus") String readyStatus);

  List<JobTaskEntity> selectActiveByAssignedWorker(JobTaskActiveByAssignedWorkerQuery parameters);

  /** 便捷重载：RUNNING / READY / CREATED 默认集合（所有调用方均用此组合）。 */
  default List<JobTaskEntity> selectActiveByAssignedWorker(String tenantId, String workerCode) {
    JobTaskActiveByAssignedWorkerQuery jobTaskActiveByAssignedWorkerQuery =
        JobTaskActiveByAssignedWorkerQuery.builder()
            .tenantId(tenantId)
            .assignedWorkerCode(workerCode)
            .runningStatus(TaskStatus.RUNNING.code())
            .readyStatus(TaskStatus.READY.code())
            .createdStatus(TaskStatus.CREATED.code())
            .build();
    return selectActiveByAssignedWorker(jobTaskActiveByAssignedWorkerQuery);
  }

  int updateStatus(UpdateTaskStatusParam param);

  /**
   * 以版本 CAS 认领任务，并直接返回更新后的行。返回 {@code null} 表示状态、版本或归属条件未命中。
   *
   * <p>使用 {@code UPDATE ... RETURNING} 避免认领成功后再次按主键查询；CAS 谓词与原认领语句一致。
   */
  JobTaskEntity assignWorker(AssignWorkerParam param);

  int resetForRetry(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("readyStatus") String readyStatus,
      @Param("expectedVersion") Long expectedVersion);

  int markExternalOutcomeResolution(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("expectedVersion") Long expectedVersion);

  int bindExternalOutcomeResolutionToWorkflowRequest(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("sourceVersion") Long sourceVersion,
      @Param("workflowTriggerRequestId") Long workflowTriggerRequestId);

  int consumeExternalOutcomeResolution(ExternalOutcomeResolutionConsumeParam parameters);

  /**
   * 租约回收专用复位：允许父 Job/Workflow 处于 PAUSED，以恢复暂停前已派发的在途任务；
   * 普通重试和人工重排队必须使用 {@link #resetForRetry}，暂停期间会被拒绝。
   */
  int resetForLeaseReclaim(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("readyStatus") String readyStatus,
      @Param("expectedVersion") Long expectedVersion);

  /**
   * 尝试用 {@code FOR UPDATE NOWAIT} 立即取得某 task 行的行锁,取不到(行正被并发事务持有)立即抛 {@link
   * org.springframework.dao.CannotAcquireLockException}(PostgreSQL SQLState 55P03),<b>绝不等待</b>。
   *
   * <p>reclaim 路径专用:reclaim 与 outcome 对同一 (task, partition) 取锁顺序相反(reclaim partition→task, outcome
   * task→partition),直接 UPDATE 的等待会构成行锁反转死锁。reclaim 是尽力而为的后台清理,先用本方法 试锁
   * task,抢不到即本轮让路、下轮重试,从而永不参与等待环。返回被锁 task 的 id(命中即 1 行);行不存在返回 {@code null}。
   */
  Long lockForReclaimNoWait(@Param("tenantId") String tenantId, @Param("id") Long id);

  int promoteStatus(JobTaskPromoteStatusParam parameters);

  /**
   * 以状态和版本 CAS 终结任务，并返回数据库中的最终行。
   *
   * <p>返回 {@code null} 表示 CAS 未命中。使用 PostgreSQL {@code UPDATE ... RETURNING}，让 report 主链路
   * 不必在成功更新后再执行一次主键查询；状态、版本和租户谓词保持不变。
   */
  JobTaskEntity finishTask(FinishTaskParam param);

  /**
   * 覆盖写 task_payload(retry/reclaim 把 RunMode 持久化到 payload 用,P1-2.2 起 Kafka message 不再透传
   * payload,worker CLAIM 时 EffectiveTaskConfig 实时读 job_task.task_payload)。
   *
   * @return 更新行数(0 表示 task 不存在或并发改写)
   */
  int updatePayload(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("taskPayload") String taskPayload);

  /**
   * ORCH-P4-1：worker 经 renew 上报进度 / checkpoint 快照。仅 RUNNING task 可写(终态后忽略)，同步刷新 heartbeat_at。
   *
   * @return 更新行数(0 表示 task 不存在 / 非 RUNNING)
   */
  int updateHeartbeatDetails(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("heartbeatDetails") String heartbeatDetails);

  /**
   * ORCH-P4-1：平台请求取消（运维 cancel 端点），置 cancel_requested=true。仅 RUNNING task 有意义。
   *
   * @return 更新行数(0 表示 task 不存在 / 非 RUNNING)
   */
  int requestCancel(@Param("tenantId") String tenantId, @Param("id") Long id);

  /** 超时扫描专用取消：仅候选版本仍匹配且当前尝试仍超时时更新。 */
  int requestCancelIfTimedOut(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("expectedVersion") Long expectedVersion);

  /**
   * 实例级 RUNNING cancel：将实例下 RUNNING task 批量置 cancel_requested=true。
   *
   * @return 实际新标记的 task 数；已标记过的 task 不重复计数。
   */
  int requestCancelByInstance(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  /**
   * ORCH-P4-2：startToClose 超时候选——RUNNING 且 started_at 已超过 task_timeout_seconds 且尚未请求取消。
   *
   * @param limit 单次扫描上限，避免长事务
   */
  List<JobTaskEntity> selectTaskTimeoutCandidates(@Param("limit") int limit);

  /**
   * 与 {@link JobPartitionMapper#closeNonTerminalPartitionsForTerminalInstance} 配对：实例终态下仍为非终态的 task
   * 批量收敛。
   */
  int closeNonTerminalTasksForTerminalInstance(
      @Param("tenantId") String tenantId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("targetTaskStatus") String targetTaskStatus);
}
