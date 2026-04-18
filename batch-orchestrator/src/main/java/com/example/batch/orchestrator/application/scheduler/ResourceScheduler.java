package com.example.batch.orchestrator.application.scheduler;

import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingDecision;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;

/**
 * 资源调度器（门面接口）。
 * 整合队列解析、并发限制、分区限流、优先级计算及 Worker 选择等子流程，
 * 输出完整的 {@link ResourceSchedulingDecision}，决定任务是否可立即分发及分配的目标 Worker。
 */
public interface ResourceScheduler {

  ResourceSchedulingDecision schedule(ResourceSchedulingRequest request);
}
