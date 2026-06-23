package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import java.time.Instant;
import java.util.List;

/** 处理 Worker 分配、任务租约管理、状态更新及日志追加，从 {@link DefaultTaskExecutionService} 中拆分。 */
public interface TaskAssignmentService {

  JobTaskEntity assignWorker(String tenantId, Long taskId, String workerCode);

  boolean renewTaskLease(
      String tenantId, Long taskId, String workerCode, String partitionInvocationId);

  /**
   * ORCH-P4-1：worker 心跳(renew)合并续租 + 进度上报 + 取消感知,单事务一次完成。
   *
   * <ul>
   *   <li>先续租 lease(语义同 {@link #renewTaskLease});续租失败 → {@code leaseRenewed=false}(controller 转
   *       409)
   *   <li>续租成功且 {@code detailsJson != null} → 持久化进度 / checkpoint 快照到 job_task.heartbeat_details
   *   <li>回读 cancel_requested 标记 → {@code cancelRequested}(SDK 据此主动停,不等 lease 超时)
   * </ul>
   *
   * @param detailsJson 进度 / checkpoint 的 JSON 文本;null 表示本次心跳不带 details
   */
  TaskHeartbeatResult recordHeartbeat(
      String tenantId,
      Long taskId,
      String workerCode,
      String partitionInvocationId,
      String detailsJson);

  /**
   * ORCH-P4-1：平台请求取消 RUNNING task(运维 cancel 端点 / ORCH-P4-2 超时逻辑)。
   *
   * @return true 表示确有 RUNNING task 被标记;false 表示 task 不存在 / 非 RUNNING / 已请求过
   */
  boolean requestCancel(String tenantId, Long taskId);

  /** ORCH-P4-1 心跳结果:{@code leaseRenewed} 续租是否成功;{@code cancelRequested} 平台是否已请求取消。 */
  record TaskHeartbeatResult(boolean leaseRenewed, boolean cancelRequested) {}

  /**
   * 加载任务的 effective config 快照(实时读 job_task / job_instance / job_partition / job_definition)。
   *
   * <p>由 {@code POST /internal/tasks/{taskId}/claim} 在 worker 认领成功后调用,把结果作为 HTTP response body 返回给
   * worker。Worker 优先用本对象的字段,缺字段时 fallback 到 {@code TaskDispatchMessage} 旧字段。
   *
   * <p>设计依据:{@code docs/design/batch-classification-and-gaps.md} §3.4 / §4 P1-2。
   *
   * @return effective config;task 不存在时返回 null
   */
  EffectiveTaskConfig loadEffectiveConfig(String tenantId, Long taskId);

  JobTaskEntity updateTaskStatus(
      String tenantId, Long taskId, String taskStatus, String errorCode, String errorMessage);

  JobExecutionLogEntity appendLog(JobExecutionLogEntity log);

  List<JobExecutionLogEntity> listLogs(String tenantId, Long jobInstanceId, Long jobPartitionId);

  JobTaskEntity markRunning(String tenantId, Long taskId, Instant startedAt);
}
