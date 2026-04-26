package com.example.batch.orchestrator.application.scheduler;

import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;

/** 优先级调度器。 根据调度请求和队列配置计算任务的整数优先级，并将其映射为优先级档位（PriorityBand）字符串。 实现类须保证优先级计算具有确定性，相同输入始终返回相同结果。 */
public interface PriorityScheduler {

  Integer resolvePriority(ResourceSchedulingRequest request, ResourceQueueRecord queue);

  String resolvePriorityBand(Integer priority);
}
