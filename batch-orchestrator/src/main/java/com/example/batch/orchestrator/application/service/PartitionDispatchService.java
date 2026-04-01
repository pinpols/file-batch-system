package com.example.batch.orchestrator.application.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.service.LaunchValidationService.LaunchLoadResult;
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

    record DispatchRequest(
            LaunchRequest request,
            Map<String, Object> effectiveParams,
            String traceId
    ) {
    }

    record DispatchRuntime(
            JobInstanceEntity jobInstance,
            WorkflowRunEntity workflowRun,
            List<WorkflowDagService.DagNodeResolution> initialNodes,
            Instant startedAt
    ) {
    }

    final class DispatchContext {

        private final DispatchRequest requestContext;
        private final DispatchRuntime runtimeContext;

        private DispatchContext(DispatchRequest requestContext, DispatchRuntime runtimeContext) {
            this.requestContext = requestContext;
            this.runtimeContext = runtimeContext;
        }

        public static DispatchContext of(DispatchRequest requestContext,
                                         DispatchRuntime runtimeContext) {
            return new DispatchContext(
                    requestContext,
                    runtimeContext
            );
        }

        public static DispatchContext of(LaunchRequest request,
                                         Map<String, Object> effectiveParams,
                                         String traceId,
                                         JobInstanceEntity jobInstance,
                                         WorkflowRunEntity workflowRun,
                                         List<WorkflowDagService.DagNodeResolution> initialNodes,
                                         Instant startedAt) {
            return of(
                    new DispatchRequest(request, effectiveParams, traceId),
                    new DispatchRuntime(jobInstance, workflowRun, initialNodes, startedAt)
            );
        }

        public LaunchRequest request() {
            return requestContext.request();
        }

        public Map<String, Object> effectiveParams() {
            return requestContext.effectiveParams();
        }

        public String traceId() {
            return requestContext.traceId();
        }

        public JobInstanceEntity jobInstance() {
            return runtimeContext.jobInstance();
        }

        public WorkflowRunEntity workflowRun() {
            return runtimeContext.workflowRun();
        }

        public List<WorkflowDagService.DagNodeResolution> initialNodes() {
            return runtimeContext.initialNodes();
        }

        public Instant startedAt() {
            return runtimeContext.startedAt();
        }
    }

    /**
     * Builds the schedule plan, creates partitions, creates tasks, writes outbox events,
     * and marks the job instance as RUNNING. Runs in its own transaction.
     */
    void dispatch(DispatchContext context);
}
