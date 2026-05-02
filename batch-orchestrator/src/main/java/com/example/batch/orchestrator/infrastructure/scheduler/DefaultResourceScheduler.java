package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.scheduler.ConcurrencyLimiter;
import com.example.batch.orchestrator.application.scheduler.PartitionThrottle;
import com.example.batch.orchestrator.application.scheduler.PriorityScheduler;
import com.example.batch.orchestrator.application.scheduler.ResourceQueueManager;
import com.example.batch.orchestrator.application.scheduler.ResourceScheduler;
import com.example.batch.orchestrator.application.scheduler.WorkerSelector;
import com.example.batch.orchestrator.domain.entity.BatchWindowEntity;
import com.example.batch.orchestrator.domain.entity.ResourceQueueEntity;
import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import com.example.batch.orchestrator.domain.param.CountActiveByGroupParam;
import com.example.batch.orchestrator.domain.scheduling.ResourceCheck;
import com.example.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import com.example.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 资源调度统一收口：给定一个 {@link ResourceSchedulingRequest}，按固定 pipeline 依次判定可派发性， 任一阶段 block 即 short-circuit
 * 返回 {@code dispatchable=false} 的决策（带 reasonCode），不再继续后续检查。
 *
 * <p>Pipeline 顺序：
 *
 * <ol>
 *   <li>{@code resourceQueueManager.resolveQueue} — 解析租户队列配置。
 *   <li>{@code priorityScheduler.resolvePriority / resolvePriorityBand} — 计算有效优先级与分档。
 *   <li>{@link #checkBatchWindow} — 当前时间是否在业务窗口内。
 *   <li>{@code concurrencyLimiter.check} — 租户/队列级并发未超限。
 *   <li>{@code partitionThrottle.check} — 分区吞吐未触顶。
 *   <li>{@code workerSelector.select} — 有在线 worker 匹配路由。
 *   <li>{@code enrichFairnessScore} — 公平分数（供 {@code WaitingPartitionDispatchScheduler} 排序用）。
 * </ol>
 *
 * <p>所有分支最终都产出 {@link ResourceSchedulingDecision}（包含队列码、worker route、priority band、 partitionStatus
 * / taskStatus 初始值），让 launch / retry / DAG dispatch 复用同一决策，而不各自散落判断。
 */
@Component
@RequiredArgsConstructor
public class DefaultResourceScheduler implements ResourceScheduler {

  private record FairnessWeights(
      Integer priority, String priorityBand, int tenantWeight, int queueWeight) {}

  private record FairnessLoad(
      int tenantActiveJobs,
      int tenantActivePartitions,
      int queueActiveJobs,
      int queueActivePartitions) {}

  private record FairnessScoreContext(FairnessWeights weights, FairnessLoad load) {}

  private final ResourceQueueManager resourceQueueManager;
  private final ConcurrencyLimiter concurrencyLimiter;
  private final PartitionThrottle partitionThrottle;
  private final WorkerSelector workerSelector;
  private final PriorityScheduler priorityScheduler;
  private final OrchestratorConfigCacheService configCacheService;
  private final JobInstanceMapper jobInstanceMapper;
  private final JobPartitionMapper jobPartitionMapper;
  private final BatchTimezoneProvider timezoneProvider;

  /** 资源调度统一收口在这里，避免 launch、retry、DAG dispatch 各自散落窗口/并发/worker 判断。 */
  @Override
  public ResourceSchedulingDecision schedule(ResourceSchedulingRequest request) {
    ResourceQueueEntity queue = resourceQueueManager.resolveQueue(request);
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
          ResourceCheck.waitForCapacity(
              "NO_AVAILABLE_WORKER", "no online worker matches current route"));
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

  private ResourceSchedulingDecision blockedDecision(
      ResourceSchedulingRequest request,
      ResourceQueueEntity queue,
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
    if (request == null
        || !Texts.hasText(request.getTenantId())
        || !Texts.hasText(request.getWindowCode())) {
      return ResourceCheck.allow();
    }
    BatchWindowEntity window =
        configCacheService.findEnabledBatchWindow(request.getTenantId(), request.getWindowCode());
    if (window == null || isWithinWindow(window)) {
      return ResourceCheck.allow();
    }
    if ("FAIL".equalsIgnoreCase(window.outOfWindowAction())) {
      return ResourceCheck.reject(
          "OUT_OF_WINDOW", "current execution time is outside batch window");
    }
    return ResourceCheck.waitForCapacity("OUT_OF_WINDOW_WAIT", "waiting for batch window");
  }

  private boolean isWithinWindow(BatchWindowEntity window) {
    if (window == null || window.startTime() == null || window.endTime() == null) {
      return true;
    }
    ZoneId zoneId = timezoneProvider.resolveOrDefault(window.timezone());
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

  private String resolveWorkerGroup(ResourceSchedulingRequest request, ResourceQueueEntity queue) {
    if (request != null && Texts.hasText(request.getWorkerGroup())) {
      return request.getWorkerGroup();
    }
    return queue == null ? null : queue.workerGroup();
  }

  private void enrichFairnessScore(
      ResourceSchedulingRequest request,
      ResourceQueueEntity queue,
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
    long fairnessScore =
        resolveFairnessScore(
            new FairnessScoreContext(
                new FairnessWeights(priority, priorityBand, tenantWeight, queueWeight),
                new FairnessLoad(
                    tenantActiveJobs,
                    tenantActivePartitions,
                    queueActiveJobs,
                    queueActivePartitions)));
    decision.setTenantWeight(tenantWeight);
    decision.setQueueWeight(queueWeight);
    decision.setTenantActiveJobs(tenantActiveJobs);
    decision.setTenantActivePartitions(tenantActivePartitions);
    decision.setQueueActiveJobs(queueActiveJobs);
    decision.setQueueActivePartitions(queueActivePartitions);
    decision.setFairnessScore(fairnessScore);
  }

  private int resolveTenantWeight(String tenantId) {
    if (!Texts.hasText(tenantId)) {
      return 1;
    }
    TenantQuotaPolicyEntity policy = configCacheService.findEnabledQuotaPolicy(tenantId);
    return hasValidFairShareWeight(policy == null ? null : policy.fairShareWeight())
        ? policy.fairShareWeight()
        : 1;
  }

  private int resolveQueueWeight(ResourceQueueEntity queue) {
    return hasValidFairShareWeight(queue == null ? null : queue.fairShareWeight())
        ? queue.fairShareWeight()
        : 1;
  }

  private boolean hasValidFairShareWeight(Integer weight) {
    return weight != null && weight > 0;
  }

  private int resolveTenantActiveJobs(ResourceSchedulingRequest request) {
    if (request == null || !Texts.hasText(request.getTenantId())) {
      return 0;
    }
    return (int) jobInstanceMapper.countActiveByTenant(request.getTenantId());
  }

  private int resolveTenantActivePartitions(ResourceSchedulingRequest request) {
    if (request == null || !Texts.hasText(request.getTenantId())) {
      return 0;
    }
    return (int)
        jobPartitionMapper.countActiveByTenant(
            request.getTenantId(),
            PartitionStatus.WAITING.code(),
            PartitionStatus.READY.code(),
            PartitionStatus.RUNNING.code(),
            PartitionStatus.RETRYING.code());
  }

  private int resolveQueueActiveJobs(ResourceSchedulingRequest request, ResourceQueueEntity queue) {
    if (request == null
        || !Texts.hasText(request.getTenantId())
        || queue == null
        || !Texts.hasText(queue.queueCode())) {
      return 0;
    }
    return (int)
        jobInstanceMapper.countActiveByTenantAndQueueCode(request.getTenantId(), queue.queueCode());
  }

  private int resolveQueueActivePartitions(
      ResourceSchedulingRequest request, ResourceQueueEntity queue) {
    if (request == null
        || !Texts.hasText(request.getTenantId())
        || queue == null
        || !Texts.hasText(queue.workerGroup())) {
      return 0;
    }
    return (int)
        jobPartitionMapper.countActiveByTenantAndWorkerGroup(
            CountActiveByGroupParam.builder()
                .tenantId(request.getTenantId())
                .workerGroup(queue.workerGroup())
                .waitingStatus(PartitionStatus.WAITING.code())
                .readyStatus(PartitionStatus.READY.code())
                .runningStatus(PartitionStatus.RUNNING.code())
                .retryingStatus(PartitionStatus.RETRYING.code())
                .build());
  }

  private long resolveFairnessScore(FairnessScoreContext context) {
    FairnessWeights weights = context.weights();
    FairnessLoad load = context.load();
    long bandWeight =
        switch (weights.priorityBand() == null ? "" : weights.priorityBand()) {
          case "HIGH" -> 300L;
          case "MEDIUM" -> 200L;
          default -> 100L;
        };
    int normalizedPriority =
        weights.priority() == null ? 5 : Math.max(1, Math.min(weights.priority(), 9));
    long loadPenalty =
        (long) load.tenantActiveJobs()
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
