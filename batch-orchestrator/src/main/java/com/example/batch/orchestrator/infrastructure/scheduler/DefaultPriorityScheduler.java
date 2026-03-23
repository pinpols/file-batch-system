package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.enums.SchedulingPriorityBand;
import com.example.batch.orchestrator.application.scheduler.PriorityScheduler;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
import org.springframework.stereotype.Component;

@Component
public class DefaultPriorityScheduler implements PriorityScheduler {

    @Override
    public Integer resolvePriority(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
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
