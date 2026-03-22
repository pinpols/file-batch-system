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

    void replayDeadLetter(String tenantId, Long deadLetterTaskId);
}
