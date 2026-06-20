package com.example.batch.orchestrator.application.service.task;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.Guard;
import com.example.batch.orchestrator.domain.command.JobInstanceTerminalStatusCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 作业实例与分区的生命周期管理应用服务，提供取消、终止和重试等状态变更操作。
 *
 * <p>状态机约束通过常量集合（{@code CANCELLABLE}、{@code TERMINABLE}）声明， 所有状态转换均基于乐观锁版本号执行 CAS
 * 更新，若并发修改导致更新行数为零则抛出 {@link com.example.batch.common.exception.BizException}，要求调用方重试。
 * 分区级操作（取消、重试）与实例级操作相互独立，允许细粒度的运维干预。
 */
@Service
@RequiredArgsConstructor
public class InstanceManagementApplicationService {

  private static final Set<String> CANCELLABLE = Set.of("CREATED", "WAITING", "READY");
  private static final Set<String> TERMINABLE = Set.of("RUNNING");
  private static final Set<String> PARTITION_CANCELLABLE = Set.of("CREATED", "WAITING", "READY");
  // ADR-044:仅 RUNNING 可暂停(停发新分区,在途自然终结);PAUSED 可恢复回 RUNNING。
  private static final Set<String> PAUSABLE = Set.of("RUNNING");
  private static final Set<String> RESUMABLE = Set.of("PAUSED");

  private final JobInstanceMapper jobInstanceMapper;
  private final JobPartitionMapper jobPartitionMapper;
  private final JobTaskMapper jobTaskMapper;
  private final JobInstanceTerminalStatusApplicationService
      jobInstanceTerminalStatusApplicationService;

  public Map<String, Object> cancel(String tenantId, Long id) {
    JobInstanceEntity instance =
        Guard.requireFound(jobInstanceMapper.selectById(tenantId, id), "job instance not found");
    if ("RUNNING".equals(instance.getInstanceStatus())) {
      int requested = jobTaskMapper.requestCancelByInstance(tenantId, id);
      return Map.of(
          "id",
          id,
          "instanceNo",
          instance.getInstanceNo(),
          "status",
          "CANCEL_REQUESTED",
          "cancelRequestedTasks",
          requested);
    }
    return transition(instance, tenantId, id, CANCELLABLE, "CANCELLED");
  }

  public Map<String, Object> terminate(String tenantId, Long id) {
    return transition(tenantId, id, TERMINABLE, "TERMINATED");
  }

  /** ADR-044 暂停 RUNNING → PAUSED:停发新分区,在途自然终结,不破坏性 kill。 */
  public Map<String, Object> pause(String tenantId, Long id) {
    return lifecycleTransition(tenantId, id, PAUSABLE, "PAUSED");
  }

  /** ADR-044 恢复 PAUSED → RUNNING:重新纳入派发,已成功分区不重跑(靠幂等)。 */
  public Map<String, Object> resume(String tenantId, Long id) {
    return lifecycleTransition(tenantId, id, RESUMABLE, "RUNNING");
  }

  /**
   * 非终态生命周期 CAS 转换(pause/resume 专用)。
   *
   * <p>不走终态 reconcile、不动 finished_at,仅 allowedFrom + version 守护。
   */
  private Map<String, Object> lifecycleTransition(
      String tenantId, Long id, Set<String> allowedFrom, String targetStatus) {
    JobInstanceEntity instance =
        Guard.requireFound(jobInstanceMapper.selectById(tenantId, id), "job instance not found");
    if (!allowedFrom.contains(instance.getInstanceStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "cannot transition from " + instance.getInstanceStatus() + " to " + targetStatus);
    }
    int rows =
        jobInstanceMapper.updateLifecycleStatus(tenantId, id, targetStatus, instance.getVersion());
    if (rows == 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
    }
    return Map.of("id", id, "instanceNo", instance.getInstanceNo(), "status", targetStatus);
  }

  public Map<String, Object> cancelPartition(String tenantId, Long id) {
    JobPartitionEntity partition = findPartition(tenantId, id);
    if (!PARTITION_CANCELLABLE.contains(partition.getPartitionStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "cannot cancel partition from " + partition.getPartitionStatus());
    }
    int rows =
        jobPartitionMapper.promoteStatus(
            tenantId, id, partition.getPartitionStatus(), "CANCELLED", partition.getVersion());
    if (rows == 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
    }
    return Map.of("id", id, "status", "CANCELLED");
  }

  public Map<String, Object> retryPartition(String tenantId, Long id) {
    JobPartitionEntity partition = findPartition(tenantId, id);
    if (!"FAILED".equals(partition.getPartitionStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "can only retry FAILED partitions, current: " + partition.getPartitionStatus());
    }
    int rows =
        jobPartitionMapper.markRetrying(
            tenantId, id, partition.getRetryCount() + 1, "RETRYING", partition.getVersion());
    if (rows == 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
    }
    return Map.of("id", id, "status", "RETRYING");
  }

  private JobPartitionEntity findPartition(String tenantId, Long id) {
    return Guard.requireFound(jobPartitionMapper.selectById(tenantId, id), "partition not found");
  }

  private Map<String, Object> transition(
      String tenantId, Long id, Set<String> allowedFrom, String targetStatus) {
    JobInstanceEntity instance =
        Guard.requireFound(jobInstanceMapper.selectById(tenantId, id), "job instance not found");
    return transition(instance, tenantId, id, allowedFrom, targetStatus);
  }

  private Map<String, Object> transition(
      JobInstanceEntity instance,
      String tenantId,
      Long id,
      Set<String> allowedFrom,
      String targetStatus) {
    if (!allowedFrom.contains(instance.getInstanceStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "cannot transition from " + instance.getInstanceStatus() + " to " + targetStatus);
    }
    JobInstanceTerminalStatusCommand cmd =
        new JobInstanceTerminalStatusCommand(
            tenantId, id, targetStatus, BatchDateTimeSupport.utcNow(), instance.getVersion());
    int rows =
        jobInstanceTerminalStatusApplicationService.updateTerminalStatusAndReconcileChildren(cmd);
    if (rows == 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
    }
    return Map.of("id", id, "instanceNo", instance.getInstanceNo(), "status", targetStatus);
  }
}
