package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;

public interface RetryGovernanceService {

    boolean scheduleRetryIfNecessary(
            JobTaskEntity task,
            JobPartitionEntity partition,
            JobInstanceEntity jobInstance,
            String errorCode,
            String errorMessage);

    void dispatchDueRetries();

    void retryPartition(String tenantId, Long partitionId, String eventKey);

    /**
     * 重新派发任务进行重试。当任务关联了分区时，委托给 {@link #retryPartition}； 当 {@code job_partition_id} 为 null
     * 时，重置任务并写入不带分区行的派发 outbox。
     */
    void retryTask(String tenantId, Long taskId, String eventKey);

    /** 强制回收可能处于 RUNNING 状态的任务（例如 Worker 排空接管期间）。 与 {@link #retryTask} 不同，此方法除终态外还接受 RUNNING 状态。 */
    void reclaimTask(String tenantId, Long taskId, String eventKey);

    void replayDeadLetter(String tenantId, Long deadLetterTaskId);
}
