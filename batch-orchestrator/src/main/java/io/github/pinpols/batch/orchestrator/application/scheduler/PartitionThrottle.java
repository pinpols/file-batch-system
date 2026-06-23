package io.github.pinpols.batch.orchestrator.application.scheduler;

import io.github.pinpols.batch.orchestrator.domain.entity.ResourceQueueEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;

/**
 * 分区级别流量限制器。 检查单次调度请求对应的分区数量是否超出队列或租户允许的最大并发分区配额。 与 {@link ConcurrencyLimiter} 互补——前者控制 Job
 * 并发，本接口控制 Partition 并发。
 */
public interface PartitionThrottle {

  ResourceCheck check(ResourceSchedulingRequest request, ResourceQueueEntity queue);
}
