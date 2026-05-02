package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.enums.SchedulingPriorityBand;
import com.example.batch.orchestrator.application.scheduler.PriorityScheduler;
import com.example.batch.orchestrator.domain.entity.ResourceQueueEntity;
import com.example.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import org.springframework.stereotype.Component;

/**
 * 优先级归一化与分档：
 *
 * <ul>
 *   <li>{@code resolvePriority}：把请求优先级 clamp 到 {@code [1, 9]}，缺省值 5（中位）。
 *   <li>{@code resolvePriorityBand}：按分段映射到 {@link SchedulingPriorityBand} 的 HIGH / MEDIUM / LOW 三档
 *       —— 边界值 {@code 3} 和 {@code 6} 是业务约定（1-3 HIGH、4-6 MEDIUM、7-9 LOW），用于 priorityBand 维度的调度统计与告警。
 * </ul>
 */
@Component
public class DefaultPriorityScheduler implements PriorityScheduler {

  @Override
  public Integer resolvePriority(ResourceSchedulingRequest request, ResourceQueueEntity queue) {
    int priority = request == null || request.getPriority() == null ? 5 : request.getPriority();
    if (priority < 1) {
      return 1;
    }
    if (priority > 9) {
      return 9;
    }
    return priority;
  }

  @Override
  public String resolvePriorityBand(Integer priority) {
    int normalizedPriority = priority == null ? 5 : priority;
    if (normalizedPriority <= 3) {
      return SchedulingPriorityBand.HIGH.code();
    }
    if (normalizedPriority <= 6) {
      return SchedulingPriorityBand.MEDIUM.code();
    }
    return SchedulingPriorityBand.LOW.code();
  }
}
