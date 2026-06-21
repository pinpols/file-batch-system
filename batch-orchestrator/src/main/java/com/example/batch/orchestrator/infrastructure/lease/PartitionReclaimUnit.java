package com.example.batch.orchestrator.infrastructure.lease;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.RunMode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单个过期 partition 的回收单元，每次以 {@code REQUIRES_NEW} 独立事务执行。
 *
 * <p>独立成 bean 是为了让 Spring AOP 代理生效——上层 scheduler 通过依赖注入调用本方法，避免 self-invocation 导致
 * {@code @Transactional} 失效。
 *
 * <p>语义升级（v6 hardening）：当任一 CAS 步骤失败时显式抛 {@link ReclaimRetryableException} 触发本事务回滚， 避免出现 "partition
 * 已 resetForDispatch（lease_expire_at 被清空）但 task 仍 RUNNING" 的死态—— 旧实现遇到第二步冲突会 return 跳过，partition
 * 已被改写但 lease 已 NULL，下一轮 selectExpiredLeasesGlobal 因 {@code lease_expire_at IS NOT NULL} 永远不再扫到该行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionReclaimUnit {

  private final JobPartitionMapper jobPartitionMapper;
  private final JobTaskMapper jobTaskMapper;
  private final JobStepInstanceMapper jobStepInstanceMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final TaskDispatchOutboxService taskDispatchOutboxService;
  private final BatchOrchestratorGovernanceProperties governance;

  /**
   * 尝试回收单个过期 partition。CAS 冲突会抛 {@link ReclaimRetryableException}，由 Spring 事务回滚。
   *
   * <p>调用方应捕获该异常后继续处理下一个 partition；本方法只负责把"成功 / 重试型失败"语义对齐到事务边界。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void reclaim(JobPartitionEntity partition) {
    List<JobTaskEntity> tasks =
        jobTaskMapper.selectByQuery(
            new JobTaskQuery(
                partition.getTenantId(),
                partition.getJobInstanceId(),
                partition.getId(),
                TaskStatus.RUNNING.code(),
                null));
    JobTaskEntity task = tasks.stream().findFirst().orElse(null);

    if (task == null) {
      // 无 RUNNING task：partition 在 READY 状态等待被 claim，直接重置版本即可。
      // CAS 冲突说明已被其他流程推进，安静放弃；不需要写 outbox（无 task 可派发）。
      int reset =
          jobPartitionMapper.resetForDispatch(
              partition.getTenantId(),
              partition.getId(),
              PartitionStatus.READY.code(),
              partition.getVersion());
      if (reset <= 0) {
        // R2-P2-5：no-task path 的 CAS 失败可能是另一并发流程已推进（良性），
        // 也可能是历史残留死态（partition=READY + lease_expire_at=NULL + 无 task）。
        // selectExpiredLeasesGlobal 过滤 lease_expire_at NOT NULL，死态行只能由此路径碰到；
        // 升级到 WARN 让运维可见持续告警；orphan-sweep 补充清理。
        log.warn(
            "reclaim skipped (no-task path) — CAS conflict on partitionId={} version={}; concurrent"
                + " reclaim or stale READY+null-lease dead-state (orphan-sweep should fix)",
            partition.getId(),
            partition.getVersion());
      }
      return;
    }

    JobInstanceEntity jobInstance =
        jobInstanceMapper.selectById(partition.getTenantId(), partition.getJobInstanceId());
    if (jobInstance == null) {
      return;
    }

    BatchMdc.withTenantAndTrace(
        jobInstance.getTenantId(),
        jobInstance.getTraceId(),
        () -> {
          BatchMdc.put(
              StructuredLogField.JOB_INSTANCE_ID,
              jobInstance.getId() == null ? null : String.valueOf(jobInstance.getId()));
          try {
            doReclaim(partition, task, jobInstance);
          } finally {
            BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
          }
        });
  }

  private void doReclaim(
      JobPartitionEntity partition, JobTaskEntity task, JobInstanceEntity jobInstance) {
    if (jobPartitionMapper.resetForDispatch(
            partition.getTenantId(),
            partition.getId(),
            PartitionStatus.READY.code(),
            partition.getVersion())
        <= 0) {
      log.debug("reclaim skipped, partition version conflict: partitionId={}", partition.getId());
      // partition CAS 失败：另一并发流程已推进过该行，本轮跳过；事务可正常提交（未做任何修改）。
      return;
    }
    if (jobTaskMapper.resetForRetry(
            partition.getTenantId(), task.getId(), TaskStatus.READY.code(), task.getVersion())
        <= 0) {
      // 第二步 CAS 失败：partition 已被本事务 reset 为 READY 但 task 仍 RUNNING。
      // 必须抛异常让本事务回滚 partition 修改，否则 partition 会卡在
      // "READY + lease_expire_at IS NULL" 死态，selectExpiredLeasesGlobal 永远扫不到。
      throw new ReclaimRetryableException(
          "task version conflict, rolling back partition reset: partitionId="
              + partition.getId()
              + ", taskId="
              + task.getId());
    }
    int resetSteps =
        jobStepInstanceMapper.resetForRetryByJobTaskId(
            partition.getTenantId(), task.getId(), 0, TaskStatus.READY.code());
    if (resetSteps <= 0) {
      throw new ReclaimRetryableException(
          "step reset missed, rolling back task/partition reset: partitionId="
              + partition.getId()
              + ", taskId="
              + task.getId());
    }

    // eventKey 包含 partition.version（reset 前读到的值），保证同一 partition 多次 reclaim 写入不同 outbox 行：
    // outbox_event 的 UNIQUE(tenant_id, event_key) 在 ON CONFLICT DO NOTHING 语义下，
    // 旧 eventKey "tenant:reclaim:pid"（不含 version）会让第 2 次起的 reclaim outbox 静默失败，
    // partition 永远等不到派发事件。
    String eventKey =
        partition.getTenantId() + ":reclaim:" + partition.getId() + ":v" + partition.getVersion();
    taskDispatchOutboxService.writeDispatchEvent(
        jobInstance, task, partition, jobInstance.getTraceId(), eventKey, RunMode.RECOVER);
    log.warn(
        "expired partition reclaimed and re-dispatched: tenantId={},"
            + " partitionId={}, taskId={}, resetSteps={}, fromVersion={}, leaseWindowSeconds={}",
        partition.getTenantId(),
        partition.getId(),
        task.getId(),
        resetSteps,
        partition.getVersion(),
        governance.partitionLease().getExpireSeconds());
  }
}
