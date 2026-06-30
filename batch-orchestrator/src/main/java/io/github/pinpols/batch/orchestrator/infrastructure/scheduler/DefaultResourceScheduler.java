package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.model.WorkerRouteModel;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.scheduler.ConcurrencyLimiter;
import io.github.pinpols.batch.orchestrator.application.scheduler.PartitionThrottle;
import io.github.pinpols.batch.orchestrator.application.scheduler.PriorityScheduler;
import io.github.pinpols.batch.orchestrator.application.scheduler.ResourceQueueManager;
import io.github.pinpols.batch.orchestrator.application.scheduler.ResourceScheduler;
import io.github.pinpols.batch.orchestrator.application.scheduler.WorkerSelector;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchWindowEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResourceQueueEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.domain.param.CountActiveByGroupParam;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceAdmissionAction;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 资源调度统一收敛：给定一个 {@link ResourceSchedulingRequest}，按固定 pipeline 依次判定可派发性， 任一阶段 block 即 short-circuit
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

  /**
   * tick 级别活跃计数缓存:在一个调度 tick 内 (如 WaitingPartitionDispatchScheduler 批量评估若干 partition) 对相同
   * (tenantId, queueCode/workerGroup) 的 4 个 COUNT 查询去重,消除重复 DB round-trip。
   *
   * <p>由调用方通过 {@link #openTickCache()} / {@link #closeTickCache()} 包裹 schedule() 批次显式开启。
   * 不开启时(默认),所有 resolveXxxActive 走原始无缓存路径,保持单次 schedule() 调用语义不变。
   */
  private static final ThreadLocal<Map<String, Long>> TICK_CACHE = new ThreadLocal<>();

  /** 调用方在批量评估前开启 tick 缓存;同一批次内对同一 key 的活跃数查询将命中本地 Map。 */
  public static void openTickCache() {
    TICK_CACHE.set(new HashMap<>());
  }

  /** 调用方必须在 finally 中关闭以避免 ThreadLocal 泄漏。 */
  public static void closeTickCache() {
    TICK_CACHE.remove();
  }

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
  private final BatchDateTimeSupport dateTimeSupport;
  private final ResourceSchedulerProperties resourceSchedulerProperties;

  /** 资源调度统一收敛在这里，避免 launch、retry、DAG dispatch 各自散落窗口/并发/worker 判断。 */
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
    decision.setAdmissionAction(ResourceAdmissionAction.ACCEPT);
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
    // V89 DEGRADE_PRIORITY: limiter 在 reasonCode 末尾打 _DEGRADED 标记，这里把决策 priority/band 降到最低，
    // 让 enrichFairnessScore 给出最低分，WaitingPartitionDispatchScheduler 的 fairness 排序自然把它沉到队尾
    boolean degraded = check.reasonCode() != null && check.reasonCode().endsWith("_DEGRADED");
    Integer effectivePriority = degraded ? 1 : priority;
    String effectiveBand = degraded ? "LOW" : priorityBand;
    ResourceSchedulingDecision decision = new ResourceSchedulingDecision();
    decision.setAdmissionAction(
        check.failFast() ? ResourceAdmissionAction.REJECT : ResourceAdmissionAction.DEFER);
    decision.setDispatchable(false);
    decision.setFailFast(check.failFast());
    decision.setReasonCode(check.reasonCode());
    decision.setReasonMessage(check.reasonMessage());
    decision.setQueueCode(queue == null ? request.getQueueCode() : queue.queueCode());
    decision.setWorkerGroup(resolveWorkerGroup(request, queue));
    decision.setPriority(effectivePriority);
    decision.setPriorityBand(effectiveBand);
    decision.setPartitionStatus(PartitionStatus.WAITING.code());
    decision.setTaskStatus(TaskStatus.CREATED.code());
    enrichFairnessScore(request, queue, effectivePriority, effectiveBand, decision);
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
    LocalTime now = dateTimeSupport.nowInstant().atZone(zoneId).toLocalTime();
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
    fairnessScore += resolveAgingBonus(request);
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

  /** long -> int 安全窄化:Long.MAX_VALUE 量级的活跃计数现实不可能,但溢出会让 fairnessScore 排序紊乱。 */
  private static int safeNarrow(long count) {
    return (int) Math.min(count, Integer.MAX_VALUE);
  }

  private long cachedCount(String key, LongSupplier loader) {
    Map<String, Long> cache = TICK_CACHE.get();
    if (cache == null) {
      return loader.getAsLong();
    }
    return cache.computeIfAbsent(key, k -> loader.getAsLong());
  }

  private int resolveTenantActiveJobs(ResourceSchedulingRequest request) {
    if (request == null || !Texts.hasText(request.getTenantId())) {
      return 0;
    }
    String tenantId = request.getTenantId();
    long count =
        cachedCount("tj:" + tenantId, () -> jobInstanceMapper.countActiveByTenant(tenantId));
    return safeNarrow(count);
  }

  private int resolveTenantActivePartitions(ResourceSchedulingRequest request) {
    if (request == null || !Texts.hasText(request.getTenantId())) {
      return 0;
    }
    String tenantId = request.getTenantId();
    long count =
        cachedCount(
            "tp:" + tenantId,
            () ->
                jobPartitionMapper.countActiveByTenant(
                    tenantId,
                    PartitionStatus.WAITING.code(),
                    PartitionStatus.READY.code(),
                    PartitionStatus.RUNNING.code(),
                    PartitionStatus.RETRYING.code()));
    return safeNarrow(count);
  }

  private int resolveQueueActiveJobs(ResourceSchedulingRequest request, ResourceQueueEntity queue) {
    if (request == null
        || !Texts.hasText(request.getTenantId())
        || queue == null
        || !Texts.hasText(queue.queueCode())) {
      return 0;
    }
    String tenantId = request.getTenantId();
    String queueCode = queue.queueCode();
    long count =
        cachedCount(
            "qj:" + tenantId + ":" + queueCode,
            () -> jobInstanceMapper.countActiveByTenantAndQueueCode(tenantId, queueCode));
    return safeNarrow(count);
  }

  private int resolveQueueActivePartitions(
      ResourceSchedulingRequest request, ResourceQueueEntity queue) {
    if (request == null
        || !Texts.hasText(request.getTenantId())
        || queue == null
        || !Texts.hasText(queue.workerGroup())) {
      return 0;
    }
    String tenantId = request.getTenantId();
    String workerGroup = queue.workerGroup();
    long count =
        cachedCount(
            "qp:" + tenantId + ":" + workerGroup,
            () ->
                jobPartitionMapper.countActiveByTenantAndWorkerGroup(
                    CountActiveByGroupParam.builder()
                        .tenantId(tenantId)
                        .workerGroup(workerGroup)
                        .waitingStatus(PartitionStatus.WAITING.code())
                        .readyStatus(PartitionStatus.READY.code())
                        .runningStatus(PartitionStatus.RUNNING.code())
                        .retryingStatus(PartitionStatus.RETRYING.code())
                        .build()));
    return safeNarrow(count);
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

  private long resolveAgingBonus(ResourceSchedulingRequest request) {
    if (request == null
        || request.getWaitingSince() == null
        || resourceSchedulerProperties == null
        || !resourceSchedulerProperties.isPriorityAgingEnabled()) {
      return 0L;
    }
    long waitedSeconds =
        Math.max(
            0L,
            Duration.between(request.getWaitingSince(), dateTimeSupport.nowInstant()).toSeconds());
    long stepSeconds = Math.max(1L, resourceSchedulerProperties.getPriorityAgingStepSeconds());
    long steps = waitedSeconds / stepSeconds;
    long bonus = steps * Math.max(0L, resourceSchedulerProperties.getPriorityAgingBonusPerStep());
    return Math.min(bonus, Math.max(0L, resourceSchedulerProperties.getPriorityAgingMaxBonus()));
  }
}
