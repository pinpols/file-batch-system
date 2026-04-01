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

    private record FairnessWeights(
            Integer priority,
            String priorityBand,
            int tenantWeight,
            int queueWeight
    ) {
    }

    private record FairnessLoad(
            int tenantActiveJobs,
            int tenantActivePartitions,
            int queueActiveJobs,
            int queueActivePartitions
    ) {
    }

    private record FairnessScoreContext(
            FairnessWeights weights,
            FairnessLoad load
    ) {
    }

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
        decision.setQueueCode(queue == null ? request.getQueueCode() : queue.queueCode());
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
        decision.setQueueCode(queue == null ? request.getQueueCode() : queue.queueCode());
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
                .filter(candidate -> request.getWindowCode().equalsIgnoreCase(candidate.windowCode()))
                .findFirst()
                .orElse(null);
        if (window == null || isWithinWindow(window)) {
            return ResourceCheck.allow();
        }
        if ("FAIL".equalsIgnoreCase(window.outOfWindowAction())) {
            return ResourceCheck.reject("OUT_OF_WINDOW", "current execution time is outside batch window");
        }
        return ResourceCheck.waitForCapacity("OUT_OF_WINDOW_WAIT", "waiting for batch window");
    }

    private boolean isWithinWindow(BatchWindowRecord window) {
        if (window == null || window.startTime() == null || window.endTime() == null) {
            return true;
        }
        ZoneId zoneId = StringUtils.hasText(window.timezone())
                ? ZoneId.of(window.timezone())
                : ZoneId.systemDefault();
        LocalTime now = ZonedDateTime.now(zoneId).toLocalTime();
        LocalTime start = window.startTime();
        LocalTime end = window.endTime();
        if (start.equals(end)) {
            return true;
        }
        boolean crossDay = Boolean.TRUE.equals(window.allowCrossDay()) || end.isBefore(start);
        if (crossDay) {
            return !now.isBefore(start) || !now.isAfter(end);
        }
        return !now.isBefore(start) && !now.isAfter(end);
    }

    private String resolveWorkerGroup(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
        if (request != null && StringUtils.hasText(request.getWorkerGroup())) {
            return request.getWorkerGroup();
        }
        return queue == null ? null : queue.workerGroup();
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
        long fairnessScore = resolveFairnessScore(new FairnessScoreContext(
                new FairnessWeights(priority, priorityBand, tenantWeight, queueWeight),
                new FairnessLoad(
                        tenantActiveJobs,
                        tenantActivePartitions,
                        queueActiveJobs,
                        queueActivePartitions
                )
        ));
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
        if (policy == null || policy.fairShareWeight() == null || policy.fairShareWeight() <= 0) {
            return 1;
        }
        return policy.fairShareWeight();
    }

    private int resolveQueueWeight(ResourceQueueRecord queue) {
        if (queue == null || queue.fairShareWeight() == null || queue.fairShareWeight() <= 0) {
            return 1;
        }
        return queue.fairShareWeight();
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
        return (int) jobPartitionMapper.countActiveByTenant(request.getTenantId(), PartitionStatus.WAITING.code(), PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), PartitionStatus.RETRYING.code());
    }

    private int resolveQueueActiveJobs(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
        if (request == null || !StringUtils.hasText(request.getTenantId()) || queue == null || !StringUtils.hasText(queue.queueCode())) {
            return 0;
        }
        return (int) jobInstanceMapper.countActiveByTenantAndQueueCode(request.getTenantId(), queue.queueCode());
    }

    private int resolveQueueActivePartitions(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
        if (request == null || !StringUtils.hasText(request.getTenantId()) || queue == null || !StringUtils.hasText(queue.workerGroup())) {
            return 0;
        }
        return (int) jobPartitionMapper.countActiveByTenantAndWorkerGroup(request.getTenantId(), queue.workerGroup(), PartitionStatus.WAITING.code(), PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), PartitionStatus.RETRYING.code());
    }

    private long resolveFairnessScore(FairnessScoreContext context) {
        FairnessWeights weights = context.weights();
        FairnessLoad load = context.load();
        long bandWeight = switch (weights.priorityBand() == null ? "" : weights.priorityBand()) {
            case "HIGH" -> 300L;
            case "MEDIUM" -> 200L;
            default -> 100L;
        };
        int normalizedPriority = weights.priority() == null ? 5 : Math.max(1, Math.min(weights.priority(), 9));
        long loadPenalty = (long) load.tenantActiveJobs()
                + load.tenantActivePartitions()
                + load.queueActiveJobs()
                + load.queueActivePartitions();
        return bandWeight * 10_000L
                + (long) normalizedPriority * 1_000L
                + (long) weights.tenantWeight() * 100L
                + (long) weights.queueWeight() * 10L
                - loadPenalty;
    }
}
