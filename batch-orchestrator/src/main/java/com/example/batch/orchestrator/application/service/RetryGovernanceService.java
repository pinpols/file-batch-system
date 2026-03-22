package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;

public interface RetryGovernanceService {

    boolean scheduleRetryIfNecessary(JobTaskEntity task,
                                     JobPartitionEntity partition,
                                     JobInstanceEntity jobInstance,
                                     String errorCode,
                                     String errorMessage);

    void dispatchDueRetries();

    void retryPartition(String tenantId, Long partitionId, String eventKey);

    /**
     * Re-dispatch a task for retry. When the task has a partition, delegates to {@link #retryPartition};
     * when {@code job_partition_id} is null, resets the task and writes a dispatch outbox without a partition row.
     */
    void retryTask(String tenantId, Long taskId, String eventKey);

    void replayDeadLetter(String tenantId, Long deadLetterTaskId);
}
