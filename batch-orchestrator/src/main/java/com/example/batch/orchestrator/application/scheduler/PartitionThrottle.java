package com.example.batch.orchestrator.application.scheduler;

import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;

/**
 * 分区级别流量限制器。
 * 检查单次调度请求对应的分区数量是否超出队列或租户允许的最大并发分区配额。
 * 与 {@link ConcurrencyLimiter} 互补——前者控制 Job 并发，本接口控制 Partition 并发。
 */
public interface PartitionThrottle {

  ResourceCheck check(ResourceSchedulingRequest request, ResourceQueueRecord queue);
}
