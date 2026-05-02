package com.example.batch.orchestrator.application.scheduler;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.domain.entity.ResourceQueueEntity;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;

/**
 * Worker 选择器（调度上下文感知）。 在已确定目标队列和任务优先级的前提下，从符合条件的 Worker 中选出最终执行节点。 与 {@link
 * com.example.batch.orchestrator.application.route.WorkerRoutingPolicy} 的区别在于：
 * 本接口可访问队列配置与优先级信息，用于带权重或亲和性的高级选择策略。
 */
public interface WorkerSelector {

  WorkerRouteModel select(
      ResourceSchedulingRequest request, ResourceQueueEntity queue, Integer priority);
}
