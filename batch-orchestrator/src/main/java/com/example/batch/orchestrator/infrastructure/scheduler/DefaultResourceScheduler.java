package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.application.scheduler.ConcurrencyLimiter;
import com.example.batch.orchestrator.application.scheduler.PartitionThrottle;
import com.example.batch.orchestrator.application.scheduler.PriorityScheduler;
import com.example.batch.orchestrator.application.scheduler.ResourceQueueManager;
import com.example.batch.orchestrator.application.scheduler.ResourceScheduler;
import com.example.batch.orchestrator.application.scheduler.WorkerSelector;
import com.example.batch.orchestrator.domain.entity.BatchWindowRecord;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingDecision;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.repository.BatchWindowRepository;
import com.example.batch.orchestrator.repository.TenantQuotaPolicyRepository;
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
    private final TenantQuotaPolicyRepository tenantQuotaPolicyRepository;
    private final JobInstanceMapper jobInstanceMapper;
    private final JobPartitionMapper jobPartitionMapper;

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
        enrichFairnessScore(request, queue, priority, priorityBand, decision);
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
        enrichFairnessScore(request, queue, priority, priorityBand, decision);
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

    private void enrichFairnessScore(ResourceSchedulingRequest request,
                                     ResourceQueueRecord queue,
                                     Integer priority,
                                     String priorityBand,
                                     ResourceSchedulingDecision decision) {
        if (decision == null) {
            return;
        }
        int tenantWeight = resolveTenantWeight(request == null ? null : request.getTenantId());
        int queueWeight = resolveQueueWeight(queue);
        int tenantActiveJobs = resolveTenantActiveJobs(request);
        int tenantActivePartitions = resolveTenantActivePartitions(request);
        int queueActiveJobs = resolveQueueActiveJobs(request, queue);
        int queueActivePartitions = resolveQueueActivePartitions(request, queue);
        long fairnessScore = resolveFairnessScore(priority, priorityBand, tenantWeight, queueWeight,
                tenantActiveJobs, tenantActivePartitions, queueActiveJobs, queueActivePartitions);
        decision.setTenantWeight(tenantWeight);
        decision.setQueueWeight(queueWeight);
        decision.setTenantActiveJobs(tenantActiveJobs);
        decision.setTenantActivePartitions(tenantActivePartitions);
        decision.setQueueActiveJobs(queueActiveJobs);
        decision.setQueueActivePartitions(queueActivePartitions);
        decision.setFairnessScore(fairnessScore);
    }

    private int resolveTenantWeight(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            return 1;
        }
        List<TenantQuotaPolicyRecord> policies = tenantQuotaPolicyRepository.findByTenantIdAndEnabled(tenantId, true);
        TenantQuotaPolicyRecord policy = policies == null || policies.isEmpty() ? null : policies.get(0);
        if (policy == null || policy.getFairShareWeight() == null || policy.getFairShareWeight() <= 0) {
            return 1;
        }
        return policy.getFairShareWeight();
    }

    private int resolveQueueWeight(ResourceQueueRecord queue) {
        if (queue == null || queue.getFairShareWeight() == null || queue.getFairShareWeight() <= 0) {
            return 1;
        }
        return queue.getFairShareWeight();
    }

    private int resolveTenantActiveJobs(ResourceSchedulingRequest request) {
        if (request == null || !StringUtils.hasText(request.getTenantId())) {
            return 0;
        }
        return (int) jobInstanceMapper.countActiveByTenant(request.getTenantId());
    }

    private int resolveTenantActivePartitions(ResourceSchedulingRequest request) {
        if (request == null || !StringUtils.hasText(request.getTenantId())) {
            return 0;
        }
        return (int) jobPartitionMapper.countActiveByTenant(request.getTenantId());
    }

    private int resolveQueueActiveJobs(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
        if (request == null || !StringUtils.hasText(request.getTenantId()) || queue == null || !StringUtils.hasText(queue.getQueueCode())) {
            return 0;
        }
        return (int) jobInstanceMapper.countActiveByTenantAndQueueCode(request.getTenantId(), queue.getQueueCode());
    }

    private int resolveQueueActivePartitions(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
        if (request == null || !StringUtils.hasText(request.getTenantId()) || queue == null || !StringUtils.hasText(queue.getWorkerGroup())) {
            return 0;
        }
        return (int) jobPartitionMapper.countActiveByTenantAndWorkerGroup(request.getTenantId(), queue.getWorkerGroup());
    }

    private long resolveFairnessScore(Integer priority,
                                      String priorityBand,
                                      int tenantWeight,
                                      int queueWeight,
                                      int tenantActiveJobs,
                                      int tenantActivePartitions,
                                      int queueActiveJobs,
                                      int queueActivePartitions) {
        long bandWeight = switch (priorityBand == null ? "" : priorityBand) {
            case "HIGH" -> 300L;
            case "MEDIUM" -> 200L;
            default -> 100L;
        };
        int normalizedPriority = priority == null ? 5 : Math.max(1, Math.min(priority, 9));
        long loadPenalty = (long) tenantActiveJobs + tenantActivePartitions + queueActiveJobs + queueActivePartitions;
        return bandWeight * 10_000L
                + (long) normalizedPriority * 1_000L
                + (long) tenantWeight * 100L
                + (long) queueWeight * 10L
                - loadPenalty;
    }
}
