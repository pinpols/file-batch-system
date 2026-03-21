package com.example.batch.orchestrator.scheduler;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.domain.entity.BatchWindowRecord;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.repository.BatchWindowRepository;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class DefaultResourceScheduler implements ResourceScheduler {

    private final ResourceQueueManager resourceQueueManager;
    private final ConcurrencyLimiter concurrencyLimiter;
    private final PartitionThrottle partitionThrottle;
    private final WorkerSelector workerSelector;
    private final PriorityScheduler priorityScheduler;
    private final BatchWindowRepository batchWindowRepository;

    /**
     * 资源调度统一收口在这里，避免 launch、retry、DAG dispatch 各自散落窗口/并发/worker 判断。
     */
    @Override
    public ResourceSchedulingDecision schedule(ResourceSchedulingRequest request) {
        ResourceQueueRecord queue = resourceQueueManager.resolveQueue(request);
        Integer priority = priorityScheduler.resolvePriority(request, queue);
        String priorityBand = priorityScheduler.resolvePriorityBand(priority);
        ResourceCheck windowCheck = checkBatchWindow(request);
        if (!windowCheck.allowed()) {
            return blockedDecision(request, queue, priority, priorityBand, windowCheck);
        }
        ResourceCheck concurrencyCheck = concurrencyLimiter.check(request, queue);
        if (!concurrencyCheck.allowed()) {
            return blockedDecision(request, queue, priority, priorityBand, concurrencyCheck);
        }
        ResourceCheck partitionCheck = partitionThrottle.check(request, queue);
        if (!partitionCheck.allowed()) {
            return blockedDecision(request, queue, priority, priorityBand, partitionCheck);
        }
        WorkerRouteModel route = workerSelector.select(request, queue, priority);
        if (route == null || !Boolean.TRUE.equals(route.getAvailable())) {
            return blockedDecision(
                    request,
                    queue,
                    priority,
                    priorityBand,
                    ResourceCheck.waitForCapacity("NO_AVAILABLE_WORKER", "no online worker matches current route")
            );
        }
        ResourceSchedulingDecision decision = new ResourceSchedulingDecision();
        decision.setDispatchable(true);
        decision.setFailFast(false);
        decision.setQueueCode(queue == null ? request.getQueueCode() : queue.getQueueCode());
        decision.setWorkerGroup(resolveWorkerGroup(request, queue));
        decision.setPriority(priority);
        decision.setPriorityBand(priorityBand);
        decision.setPartitionStatus(PartitionStatus.CREATED.code());
        decision.setTaskStatus(TaskStatus.CREATED.code());
        decision.setRoute(route);
        return decision;
    }

    private ResourceSchedulingDecision blockedDecision(ResourceSchedulingRequest request,
                                                       ResourceQueueRecord queue,
                                                       Integer priority,
                                                       String priorityBand,
                                                       ResourceCheck check) {
        ResourceSchedulingDecision decision = new ResourceSchedulingDecision();
        decision.setDispatchable(false);
        decision.setFailFast(check.failFast());
        decision.setReasonCode(check.reasonCode());
        decision.setReasonMessage(check.reasonMessage());
        decision.setQueueCode(queue == null ? request.getQueueCode() : queue.getQueueCode());
        decision.setWorkerGroup(resolveWorkerGroup(request, queue));
        decision.setPriority(priority);
        decision.setPriorityBand(priorityBand);
        decision.setPartitionStatus(PartitionStatus.WAITING.code());
        decision.setTaskStatus(TaskStatus.CREATED.code());
        return decision;
    }

    private ResourceCheck checkBatchWindow(ResourceSchedulingRequest request) {
        if (request == null || !StringUtils.hasText(request.getTenantId()) || !StringUtils.hasText(request.getWindowCode())) {
            return ResourceCheck.allow();
        }
        List<BatchWindowRecord> windows = batchWindowRepository.findByTenantIdAndEnabled(request.getTenantId(), true);
        BatchWindowRecord window = windows.stream()
                .filter(candidate -> request.getWindowCode().equalsIgnoreCase(candidate.getWindowCode()))
                .findFirst()
                .orElse(null);
        if (window == null || isWithinWindow(window)) {
            return ResourceCheck.allow();
        }
        if ("FAIL".equalsIgnoreCase(window.getOutOfWindowAction())) {
            return ResourceCheck.reject("OUT_OF_WINDOW", "current execution time is outside batch window");
        }
        return ResourceCheck.waitForCapacity("OUT_OF_WINDOW_WAIT", "waiting for batch window");
    }

    private boolean isWithinWindow(BatchWindowRecord window) {
        if (window == null || window.getStartTime() == null || window.getEndTime() == null) {
            return true;
        }
        ZoneId zoneId = StringUtils.hasText(window.getTimezone())
                ? ZoneId.of(window.getTimezone())
                : ZoneId.systemDefault();
        LocalTime now = ZonedDateTime.now(zoneId).toLocalTime();
        LocalTime start = window.getStartTime();
        LocalTime end = window.getEndTime();
        if (start.equals(end)) {
            return true;
        }
        boolean crossDay = Boolean.TRUE.equals(window.getAllowCrossDay()) || end.isBefore(start);
        if (crossDay) {
            return !now.isBefore(start) || !now.isAfter(end);
        }
        return !now.isBefore(start) && !now.isAfter(end);
    }

    private String resolveWorkerGroup(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
        if (request != null && StringUtils.hasText(request.getWorkerGroup())) {
            return request.getWorkerGroup();
        }
        return queue == null ? null : queue.getWorkerGroup();
    }
}
