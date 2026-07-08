package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import java.time.Instant;
import java.util.List;

/** 处理 Worker 分配、任务租约管理、状态更新及日志追加，从 {@link DefaultTaskExecutionService} 中拆分。 */
public interface TaskAssignmentService {

  JobTaskEntity assignWorker(String tenantId, Long taskId, String workerCode);

  /**
   * PERF(5.2c): 带请求级 worker 解析缓存的认领 —— 同一次 claimBatch 内同 (tenant, workerCode) 只查一次
   * worker_registry。memo 生命周期=单次 HTTP 批请求（毫秒级），不引入跨请求 staleness；传 null 等价于三参版。
   *
   * <p>不复用 {@code WorkerRegistryCache}：其键形态是 (tenant, workerGroup)→ONLINE 列表（供派发 selector），与认领侧按
   * workerCode 单点查询 + fallback 租户语义不匹配，且 5s TTL 会放宽 claim 的 ONLINE 时效门槛。
   */
  JobTaskEntity assignWorker(
      String tenantId, Long taskId, String workerCode, WorkerLookupMemo workerMemo);

  /** PERF(5.2c): 请求级 (tenant,workerCode)→worker_registry 解析结果缓存（含 miss），见 {@link #assignWorker}。 */
  final class WorkerLookupMemo {
    private final java.util.Map<String, java.util.Optional<WorkerRegistryEntity>> cache =
        new java.util.HashMap<>();

    /** 命中即返回缓存结果（含缓存的 miss=null）；未命中调 loader 并缓存。 */
    public WorkerRegistryEntity resolve(
        String tenantId,
        String workerCode,
        java.util.function.BiFunction<String, String, WorkerRegistryEntity> loader) {
      return cache
          .computeIfAbsent(
              tenantId + "\u0000" + workerCode,
              key -> java.util.Optional.ofNullable(loader.apply(tenantId, workerCode)))
          .orElse(null);
    }
  }

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
   * PERF(5.3): 批量续租(set-based)—— renewBatch 的 N 项在一条 {@code UPDATE ... FROM VALUES ... RETURNING}
   * 里完成续租校验 + lease 更新 + cancel_requested 回读;逐项语义与逐条 {@link #recordHeartbeat}(detailsJson=null)一致:
   * 校验不过 / CAS 未命中的项返回 {@code (false,false)},不影响其余项。
   *
   * @return 与入参逐位对齐的结果列表
   */
  List<TaskHeartbeatResult> renewLeaseBatch(List<LeaseRenewCommand> items);

  /** PERF(5.3): 批量续租单项入参(tenantId/taskId/workerCode/invocationId 语义同单条 renew)。 */
  record LeaseRenewCommand(
      String tenantId, Long taskId, String workerCode, String partitionInvocationId) {}

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

  /** PERF(5.2b): 复用调用方已持有的 task 实体，省掉一次 job_task selectById；task 为 null 时返回 null。 */
  EffectiveTaskConfig loadEffectiveConfig(String tenantId, JobTaskEntity task);

  JobTaskEntity updateTaskStatus(
      String tenantId, Long taskId, String taskStatus, String errorCode, String errorMessage);

  JobExecutionLogEntity appendLog(JobExecutionLogEntity log);

  List<JobExecutionLogEntity> listLogs(String tenantId, Long jobInstanceId, Long jobPartitionId);

  JobTaskEntity markRunning(String tenantId, Long taskId, Instant startedAt);
}
