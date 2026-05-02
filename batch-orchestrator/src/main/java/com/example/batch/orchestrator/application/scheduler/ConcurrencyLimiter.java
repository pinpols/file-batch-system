package com.example.batch.orchestrator.application.scheduler;

import com.example.batch.orchestrator.domain.entity.ResourceQueueEntity;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;

/**
 * 并发度限制器。 检查当前调度请求是否超出队列或租户级别的最大并发约束，返回 {@link ResourceCheck} 决策。
 * 实现类须基于实时活跃任务数做判断，允许、等待容量或直接拒绝三态之一必然被返回。
 */
public interface ConcurrencyLimiter {

  ResourceCheck check(ResourceSchedulingRequest request, ResourceQueueEntity queue);
}
