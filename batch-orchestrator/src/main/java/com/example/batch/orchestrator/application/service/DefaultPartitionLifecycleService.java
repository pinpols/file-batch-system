package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.mapper.ClaimPartitionParam;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.MarkPartitionStatusParam;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * 分片生命周期的状态主机：所有 {@code job_partition} 的状态转换（CREATED → READY → RUNNING → 终态）集中在此。
 *
 * <p>约束：
 *
 * <ul>
 *   <li>所有转换以 {@code version} 做乐观锁，CAS 失败时必须回滚或让调用方感知；不得静默吞掉。
 *   <li>{@link #releaseForDispatch} 是 READY 出队的唯一入口——分片与任务状态须原子一起推进， 见方法内的回滚并还原内存对象逻辑。
 *   <li>{@link #reclaimExpiredPartitions} 回收超租后一律回 {@code WAITING} 而非 {@code READY}，
 *       走一遍正常调度与派发，避免跳过 outbox 落库导致任务永远拿不到 Kafka 消息。
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DefaultPartitionLifecycleService implements PartitionLifecycleService {

  private final JobPartitionMapper jobPartitionMapper;
  private final JobTaskMapper jobTaskMapper;

  @Override
  @Transactional
  public List<JobPartitionEntity> createPartitions(
      SchedulePlan plan, Long jobInstanceId, String initialStatus) {
    List<JobPartitionEntity> partitionEntities = new ArrayList<>();
    if (plan == null || plan.getPartitions() == null) {
      return partitionEntities;
    }
    for (SchedulePlan.PartitionPlan partitionPlan : plan.getPartitions()) {
      JobPartitionEntity partitionEntity = new JobPartitionEntity();
      partitionEntity.setTenantId(plan.getTenantId());
      partitionEntity.setJobInstanceId(jobInstanceId);
      partitionEntity.setPartitionNo(partitionPlan.getPartitionNo());
      partitionEntity.setPartitionKey(partitionPlan.getPartitionKey());
      partitionEntity.setPartitionStatus(
          resolveInitialPartitionStatus(partitionPlan, initialStatus));
      partitionEntity.setWorkerGroup(plan.getWorkerGroup());
      partitionEntity.setWorkerCode(
          partitionPlan.getWorkerRoute() == null
              ? null
              : partitionPlan.getWorkerRoute().getWorkerCode());
      partitionEntity.setVersion(0L);
      partitionEntity.setRetryCount(0);
      partitionEntity.setBusinessKey(partitionPlan.getBusinessKey());
      // idempotencyKey 绑定到 jobInstanceId，避免 FIXED_RATE 等同一天多次触发时
      // 不同 job_instance 的分区互相冲突（partitionKey 仅作业务分片键，不作全局唯一约束）
      partitionEntity.setIdempotencyKey(jobInstanceId + ":" + partitionPlan.getPartitionNo());
      partitionEntity.setInputSnapshot(buildInputSnapshot(plan, partitionPlan, jobInstanceId));
      jobPartitionMapper.insert(partitionEntity);
      partitionEntities.add(partitionEntity);
    }
    return partitionEntities;
  }

  @Override
  @Transactional
  public JobPartitionEntity claimPartition(
      String tenantId, Long partitionId, String workerCode, Instant leaseExpireAt) {
    JobPartitionEntity existingPartition = jobPartitionMapper.selectById(tenantId, partitionId);
    if (existingPartition == null) {
      return null;
    }
    int updated =
        jobPartitionMapper.claimPartition(
            ClaimPartitionParam.builder()
                .tenantId(tenantId)
                .id(partitionId)
                .workerCode(workerCode)
                .leaseExpireAt(leaseExpireAt)
                .fromStatus(PartitionStatus.READY.code())
                .toStatus(PartitionStatus.RUNNING.code())
                .expectedVersion(existingPartition.getVersion())
                .build());
    return updated > 0 ? jobPartitionMapper.selectById(tenantId, partitionId) : existingPartition;
  }

  @Override
  @Transactional
  public JobPartitionEntity renewLease(
      String tenantId, Long partitionId, String workerCode, Instant leaseExpireAt) {
    JobPartitionEntity existingPartition = jobPartitionMapper.selectById(tenantId, partitionId);
    if (existingPartition == null) {
      return null;
    }
    int updated = jobPartitionMapper.renewLease(tenantId, partitionId, workerCode, leaseExpireAt);
    return updated > 0 ? jobPartitionMapper.selectById(tenantId, partitionId) : existingPartition;
  }

  /**
   * 回收租约到期的分片：状态推回 {@code WAITING}（而非 {@code READY}），让分片重新走一遍调度决策 + outbox 派发，防止直接回 READY 跳过 outbox
   * 落库导致任务没有 Kafka 消息可消费、永远无人认领。
   */
  @Override
  @Transactional
  public int reclaimExpiredPartitions(String tenantId) {
    int reclaimed = 0;
    List<JobPartitionEntity> expired =
        jobPartitionMapper.selectExpiredLeases(
            tenantId, PartitionStatus.READY.code(), PartitionStatus.RUNNING.code());
    for (JobPartitionEntity partition : expired) {
      reclaimed +=
          jobPartitionMapper.markStatus(
              MarkPartitionStatusParam.builder()
                  .tenantId(tenantId)
                  .id(partition.getId())
                  .partitionStatus(PartitionStatus.WAITING.code())
                  .runningStatus(PartitionStatus.RUNNING.code())
                  .terminalStatus1(PartitionStatus.SUCCESS.code())
                  .terminalStatus2(PartitionStatus.FAILED.code())
                  .terminalStatus3(PartitionStatus.CANCELLED.code())
                  .terminalStatus4(PartitionStatus.TERMINATED.code())
                  .expectedVersion(partition.getVersion())
                  .build());
    }
    return reclaimed;
  }

  /** READY 出队前统一做分片与任务状态同步推进，避免 launch、DAG dispatch、waiting release 各写一套状态机。 */
  @Override
  @Transactional
  public boolean releaseForDispatch(
      JobPartitionEntity partition,
      JobTaskEntity task,
      String fromPartitionStatus,
      String fromTaskStatus) {
    if (partition == null || task == null) {
      return false;
    }
    int partitionUpdated =
        jobPartitionMapper.promoteStatus(
            partition.getTenantId(),
            partition.getId(),
            fromPartitionStatus,
            PartitionStatus.READY.code(),
            partition.getVersion());
    if (partitionUpdated <= 0) {
      return false;
    }
    int taskUpdated =
        jobTaskMapper.promoteStatus(
            task.getTenantId(),
            task.getId(),
            fromTaskStatus,
            TaskStatus.READY.code(),
            task.getVersion());
    if (taskUpdated <= 0) {
      // 分片与任务是”必须一起推进”的原子语义：
      // - partition 已 READY 但 task 还没 READY，会导致派发链路选择/查询出现不一致
      // 因此这里宁可回滚重试，也不要留下半推进状态。
      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      // C-2.3: 事务回滚后 DB 状态不变，必须同步还原内存对象，避免调用方持有”已推进”的脏状态
      partition.setPartitionStatus(fromPartitionStatus);
      task.setTaskStatus(fromTaskStatus);
      return false;
    }
    partition.setPartitionStatus(PartitionStatus.READY.code());
    partition.setVersion(partition.getVersion() == null ? 1L : partition.getVersion() + 1);
    task.setTaskStatus(TaskStatus.READY.code());
    task.setVersion(task.getVersion() == null ? 1L : task.getVersion() + 1);
    return true;
  }

  private String resolveInitialPartitionStatus(
      SchedulePlan.PartitionPlan partitionPlan, String initialStatus) {
    if (partitionPlan != null
        && partitionPlan.getPartitionStatus() != null
        && !partitionPlan.getPartitionStatus().isBlank()) {
      return partitionPlan.getPartitionStatus();
    }
    return initialStatus == null || initialStatus.isBlank()
        ? PartitionStatus.CREATED.code()
        : initialStatus;
  }

  private String buildInputSnapshot(
      SchedulePlan plan, SchedulePlan.PartitionPlan partitionPlan, Long jobInstanceId) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("jobInstanceId", jobInstanceId);
    snapshot.put("tenantId", plan == null ? null : plan.getTenantId());
    snapshot.put("jobCode", plan == null ? null : plan.getJobCode());
    snapshot.put("bizDate", plan == null ? null : plan.getBizDate());
    snapshot.put("partitionNo", partitionPlan == null ? null : partitionPlan.getPartitionNo());
    snapshot.put("partitionKey", partitionPlan == null ? null : partitionPlan.getPartitionKey());
    snapshot.put("businessKey", partitionPlan == null ? null : partitionPlan.getBusinessKey());
    snapshot.put(
        "workerRoute",
        partitionPlan == null || partitionPlan.getWorkerRoute() == null
            ? Map.of()
            : partitionPlan.getWorkerRoute());
    snapshot.put("workerGroup", plan == null ? null : plan.getWorkerGroup());
    snapshot.put("queueCode", plan == null ? null : plan.getQueueCode());
    snapshot.put("windowCode", plan == null ? null : plan.getWindowCode());
    return JsonUtils.toJson(snapshot);
  }
}
