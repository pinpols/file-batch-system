package com.example.batch.orchestrator.application.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 为已启动的 Job 创建分区、任务及 Outbox 事件，在独立事务（T2）中执行，与 Job 实例创建事务（T1）分离以降低锁持有时长。 从 {@link
 * com.example.batch.orchestrator.service.DefaultLaunchService} 中拆分。
 */
public interface PartitionDispatchService {

    record DispatchRequest(
            LaunchRequest request, Map<String, Object> effectiveParams, String traceId) {}

    record DispatchRuntime(
            JobInstanceEntity jobInstance,
            WorkflowRunEntity workflowRun,
            List<WorkflowDagService.DagNodeResolution> initialNodes,
            Instant startedAt) {}

    final class DispatchContext {

        private final DispatchRequest requestContext;
        private final DispatchRuntime runtimeContext;

        private DispatchContext(DispatchRequest requestContext, DispatchRuntime runtimeContext) {
            this.requestContext = requestContext;
            this.runtimeContext = runtimeContext;
        }

        public static DispatchContext of(
                DispatchRequest requestContext, DispatchRuntime runtimeContext) {
            return new DispatchContext(requestContext, runtimeContext);
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

    /** 构建调度计划，创建分区、任务，写入 Outbox 事件，并将 Job 实例标记为 RUNNING，在独立事务中执行。 */
    void dispatch(DispatchContext context);
}
