package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import java.time.Instant;
import java.util.List;

/**
 * 分区生命周期管理服务。 负责 JobPartition 的创建、认领（CLAIM）、租约续期、过期回收以及状态释放等全生命周期操作。 默认的 {@code createPartitions} 以
 * {@code CREATED} 状态初始化分区； {@code releaseForDispatch} 须以乐观并发方式校验 fromStatus，确保分区状态机的单向推进。
 */
public interface PartitionLifecycleService {

  default List<JobPartitionEntity> createPartitions(SchedulePlan plan, Long jobInstanceId) {
    return createPartitions(
        plan, jobInstanceId, com.example.batch.common.enums.PartitionStatus.CREATED.code());
  }

  List<JobPartitionEntity> createPartitions(
      SchedulePlan plan, Long jobInstanceId, String initialStatus);

  JobPartitionEntity claimPartition(
      String tenantId, Long partitionId, String workerCode, Instant leaseExpireAt);

  JobPartitionEntity renewLease(
      String tenantId, Long partitionId, String workerCode, Instant leaseExpireAt);

  int reclaimExpiredPartitions(String tenantId);

  boolean releaseForDispatch(
      JobPartitionEntity partition,
      JobTaskEntity task,
      String fromPartitionStatus,
      String fromTaskStatus);
}
