package com.example.batch.orchestrator.mapper;

import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.param.AssignWorkerParam;
import com.example.batch.orchestrator.domain.param.FinishTaskParam;
import com.example.batch.orchestrator.domain.param.UpdateTaskStatusParam;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobTaskMapper {

  int insert(JobTaskEntity entity);

  JobTaskEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  JobTaskEntity selectByPartitionAndSeq(
      @Param("tenantId") String tenantId,
      @Param("jobPartitionId") Long jobPartitionId,
      @Param("taskSeq") Integer taskSeq);

  List<JobTaskEntity> selectByQuery(JobTaskQuery query);

  List<JobTaskEntity> selectReadyTasks(
      @Param("tenantId") String tenantId,
      @Param("batchSize") int batchSize,
      @Param("readyStatus") String readyStatus);

  List<JobTaskEntity> selectActiveByAssignedWorker(
      @Param("tenantId") String tenantId,
      @Param("assignedWorkerCode") String assignedWorkerCode,
      @Param("runningStatus") String runningStatus,
      @Param("readyStatus") String readyStatus,
      @Param("createdStatus") String createdStatus);

  /** 便捷重载：RUNNING / READY / CREATED 默认集合（所有调用方均用此组合）。 */
  default List<JobTaskEntity> selectActiveByAssignedWorker(String tenantId, String workerCode) {
    return selectActiveByAssignedWorker(
        tenantId,
        workerCode,
        TaskStatus.RUNNING.code(),
        TaskStatus.READY.code(),
        TaskStatus.CREATED.code());
  }

  int updateStatus(UpdateTaskStatusParam param);

  int assignWorker(AssignWorkerParam param);

  int resetForRetry(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("readyStatus") String readyStatus,
      @Param("expectedVersion") Long expectedVersion);

  int promoteStatus(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("fromStatus") String fromStatus,
      @Param("toStatus") String toStatus,
      @Param("expectedVersion") Long expectedVersion);

  int finishTask(FinishTaskParam param);

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
   * ORCH-P4-1：平台请求取消(运维 cancel 端点 / ORCH-P4-2 超时)，置 cancel_requested=true。仅 RUNNING task 有意义。
   *
   * @return 更新行数(0 表示 task 不存在 / 非 RUNNING)
   */
  int requestCancel(@Param("tenantId") String tenantId, @Param("id") Long id);

  /**
   * ORCH-P4-2：startToClose 超时候选——RUNNING 且 started_at 已超过 task_timeout_seconds 且尚未请求取消。
   *
   * @param limit 单次扫描上限，避免长事务
   */
  List<JobTaskEntity> selectTaskTimeoutCandidates(@Param("limit") int limit);

  /**
   * 与 {@link JobPartitionMapper#closeNonTerminalPartitionsForTerminalInstance} 配对：实例终态下仍为非终态的 task
   * 批量收口。
   */
  int closeNonTerminalTasksForTerminalInstance(
      @Param("tenantId") String tenantId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("targetTaskStatus") String targetTaskStatus);
}
