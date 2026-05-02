package com.example.batch.orchestrator.application.service.task;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import java.time.Instant;
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

  private final JobInstanceMapper jobInstanceMapper;
  private final JobPartitionMapper jobPartitionMapper;

  public Map<String, Object> cancel(String tenantId, Long id) {
    return transition(tenantId, id, CANCELLABLE, "CANCELLED");
  }

  public Map<String, Object> terminate(String tenantId, Long id) {
    return transition(tenantId, id, TERMINABLE, "TERMINATED");
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
    if (!allowedFrom.contains(instance.getInstanceStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "cannot transition from " + instance.getInstanceStatus() + " to " + targetStatus);
    }
    int rows =
        jobInstanceMapper.updateStatus(
            tenantId, id, targetStatus, Instant.now(), instance.getVersion());
    if (rows == 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
    }
    return Map.of("id", id, "instanceNo", instance.getInstanceNo(), "status", targetStatus);
  }
}
