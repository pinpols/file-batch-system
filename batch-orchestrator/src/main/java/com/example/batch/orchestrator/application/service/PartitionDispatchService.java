package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.service.LaunchValidationService.LaunchLoadResult;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.application.service.WorkflowDagService;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Creates partitions, tasks, and outbox events for a launched job.
 * Runs in its own {@code @Transactional} boundary (T2), separate from the
 * job-instance creation transaction (T1) to reduce lock duration.
 * Extracted from {@link com.example.batch.orchestrator.service.DefaultLaunchService}.
 */
public interface PartitionDispatchService {

    /**
     * Builds the schedule plan, creates partitions, creates tasks, writes outbox events,
     * and marks the job instance as RUNNING. Runs in its own transaction.
     */
    void dispatch(LaunchRequest request,
                  Map<String, Object> effectiveParams,
                  String traceId,
                  JobInstanceEntity jobInstance,
                  WorkflowRunEntity workflowRun,
                  List<WorkflowDagService.DagNodeResolution> initialNodes,
                  Instant startedAt);
}
