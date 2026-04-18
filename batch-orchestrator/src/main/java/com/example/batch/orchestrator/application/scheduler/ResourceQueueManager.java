package com.example.batch.orchestrator.application.scheduler;

import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;

/**
 * 资源队列管理器。
 * 根据调度请求解析并返回匹配的资源队列配置（{@link ResourceQueueRecord}），
 * 为后续并发限制、优先级计算等环节提供队列上下文。
 * 实现类须在找不到匹配队列时回退到租户默认队列或抛出明确的业务异常。
 */
public interface ResourceQueueManager {

  ResourceQueueRecord resolveQueue(ResourceSchedulingRequest request);
}
