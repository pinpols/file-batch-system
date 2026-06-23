package io.github.pinpols.batch.orchestrator.application.scheduler;

import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;

/**
 * 资源调度器（门面接口）。 整合队列解析、并发限制、分区限流、优先级计算及 Worker 选择等子流程， 输出完整的 {@link
 * ResourceSchedulingDecision}，决定任务是否可立即分发及分配的目标 Worker。
 */
public interface ResourceScheduler {

  ResourceSchedulingDecision schedule(ResourceSchedulingRequest request);
}
