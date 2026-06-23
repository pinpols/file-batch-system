package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.logging.ThrottledLogger;
import io.github.pinpols.batch.common.model.WorkerRouteModel;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.scheduler.WorkerSelector;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.ResourceQueueEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Worker 路由选择：从 ONLINE worker 中挑一个承接任务。
 *
 * <p>筛选链：
 *
 * <ol>
 *   <li>按 {@code (tenantId, workerGroup, status=ONLINE)} 过滤——workerGroup 缺省时退化为仅按租户 + ONLINE 过滤。
 *       <b>只选 ONLINE</b>：DRAINING / DECOMMISSIONED 状态的 worker 即使 heartbeat 仍在也不选 （与 {@link
 *       io.github.pinpols.batch.orchestrator.service.DefaultWorkerRegistryService} 的"状态不回退"不变量呼应）。
 *   <li>按 {@code resourceTag} 匹配队列要求（队列无标签则全通过）；worker 侧既可用 {@code resource_tag} 单值，也可用 {@code
 *       capability_tags} JSONB 数组声明多个能力。队列 tag 与 worker 单值相等、或命中 worker 能力数组中的任意一项均视为匹配（忽略大小写）。
 *   <li>排序 {@code (currentLoad asc, heartbeatAt desc)}：当前负载最小优先，并列时心跳最新者优先—— 同时兼顾负载均衡与活跃度（最近心跳的
 *       worker 状态最可信）。
 * </ol>
 *
 * <p>找不到匹配 worker 时返回 {@code available=false} 的 route（不返回 null），让调用方拿到 reasonCode。
 */
@Slf4j
@Component
public class DefaultWorkerSelector implements WorkerSelector {

  // A-3.2 a: 指标名对齐 batch.scheduler.* 前缀，和现有 scheduler 指标共面板
  private static final String METRIC_NO_MATCH = "batch.scheduler.worker_selection.no_match";

  // 同一 (tenant, group, reason) 的 no-match 在 30s 内只 WARN 一次,避免单个长期失配把日志冲掉。
  // 指标侧仍按调用次数累加,Grafana 看趋势不受影响。
  private static final Duration NO_MATCH_LOG_COOLDOWN = Duration.ofSeconds(30);

  private final ThrottledLogger noMatchLogThrottle = new ThrottledLogger(NO_MATCH_LOG_COOLDOWN);

  private final WorkerRegistryMapper workerRegistryMapper;
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;
  private final ResourceSchedulerProperties resourceSchedulerProperties;
  private final ObjectProvider<WorkerRegistryCache> workerRegistryCacheProvider;

  public DefaultWorkerSelector(
      WorkerRegistryMapper workerRegistryMapper,
      ObjectProvider<MeterRegistry> meterRegistryProvider,
      ResourceSchedulerProperties resourceSchedulerProperties,
      ObjectProvider<WorkerRegistryCache> workerRegistryCacheProvider) {
    this.workerRegistryMapper = workerRegistryMapper;
    this.meterRegistryProvider = meterRegistryProvider;
    this.resourceSchedulerProperties = resourceSchedulerProperties;
    this.workerRegistryCacheProvider = workerRegistryCacheProvider;
  }

  @Override
  public WorkerRouteModel select(
      ResourceSchedulingRequest request, ResourceQueueEntity queue, Integer priority) {
    WorkerRouteModel route = new WorkerRouteModel();
    route.setWorkerType(request == null ? null : request.getWorkerType());
    route.setPriority(priority);
    route.setResourceProfile(queue == null ? null : queue.resourceTag());
    if (request == null || !Texts.hasText(request.getTenantId())) {
      route.setAvailable(false);
      return route;
    }
    // 历史数据可能存在 IMPORT / import 大小写不一致；入口统一按大写比较回退，
    // 长期由 V64__normalize_code_conventions.sql 把 DB 存量归一，之后这里 toUpper 就是纯防御性动作。
    String workerGroup = CodeNormalizer.toUpperOrNull(resolveWorkerGroup(request, queue));
    List<WorkerRegistryEntity> candidates = findCandidates(request.getTenantId(), workerGroup);
    WorkerRegistryEntity selected = pickBest(candidates, queue);

    // 共享 worker 池 fallback（仅本地联调 / 共享 dev 环境）：主租户查不到 ONLINE worker 时，
    // 按 batch.resource-scheduler.shared-tenant-fallback 配置的租户再查一次。
    // 生产 profile 不应设置此配置，以保留 CLAUDE.md §多租户隔离 的原严格语义。
    String fallbackTenant = resourceSchedulerProperties.getSharedTenantFallback();
    if (selected == null
        && Texts.hasText(fallbackTenant)
        && !fallbackTenant.equals(request.getTenantId())) {
      List<WorkerRegistryEntity> fallbackCandidates = findCandidates(fallbackTenant, workerGroup);
      WorkerRegistryEntity fallbackSelected = pickBest(fallbackCandidates, queue);
      if (fallbackSelected != null) {
        log.info(
            "worker selection fell back to shared tenant: tenantId={}, fallbackTenant={},"
                + " workerGroup={}, workerCode={}",
            request.getTenantId(),
            fallbackTenant,
            workerGroup,
            fallbackSelected.workerCode());
        route.setWorkerCode(fallbackSelected.workerCode());
        route.setAvailable(true);
        return route;
      }
    }

    if (selected == null) {
      // A-3.2 a: 空集不再静默阻塞——记 WARN + 计数，让 Grafana / 告警能发现：
      //   - candidates.isEmpty 说明整组 worker 下线（或 workerGroup 配错）
      //   - resourceTag 全不匹配说明 queue 配置与 worker 实际 tag 失配
      // 保留阻塞语义以确保任务不会跑到错误环境（安全优先，见 v3 A-3.2）。
      String reason =
          candidates.isEmpty() ? "no_online_workers_in_group" : "no_worker_matches_resource_tag";
      String resourceTag = queue == null ? null : queue.resourceTag();
      String throttleKey = request.getTenantId() + '|' + workerGroup + '|' + reason;
      ThrottledLogger.Decision decision = noMatchLogThrottle.evaluate(throttleKey);
      if (decision.shouldLog()) {
        log.warn(
            "worker selection returned no match: tenantId={}, workerGroup={}, resourceTag={},"
                + " candidates={}, reason={}, suppressedSincePrevious={} — task will block in"
                + " WAITING until operator intervenes",
            request.getTenantId(),
            workerGroup,
            resourceTag,
            candidates.size(),
            reason,
            decision.suppressedSincePrevious());
      }
      incrementNoMatchCounter(request, queue, reason);
      route.setAvailable(false);
      return route;
    }
    route.setWorkerCode(selected.workerCode());
    route.setAvailable(true);
    return route;
  }

  private List<WorkerRegistryEntity> findCandidates(String tenantId, String workerGroup) {
    Supplier<List<WorkerRegistryEntity>> loader =
        () ->
            Texts.hasText(workerGroup)
                ? workerRegistryMapper.selectByTenantAndWorkerGroupAndStatus(
                    tenantId, workerGroup, WorkerRegistryStatus.ONLINE.code())
                : workerRegistryMapper.selectByTenantAndStatus(
                    tenantId, WorkerRegistryStatus.ONLINE.code());
    WorkerRegistryCache cache = workerRegistryCacheProvider.getIfAvailable();
    if (cache == null) {
      return loader.get();
    }
    return cache.getOrLoad(tenantId, workerGroup, loader);
  }

  private WorkerRegistryEntity pickBest(
      List<WorkerRegistryEntity> candidates, ResourceQueueEntity queue) {
    return candidates.stream()
        .filter(candidate -> matchesResourceTag(candidate, queue))
        // V87 反压闸门: current_load >= max_concurrent 的 worker 满载, skip
        // (默认 max_concurrent=10; 全 group 满则 partition 退化 WAITING)
        .filter(DefaultWorkerSelector::hasCapacity)
        .min(
            Comparator.comparingInt(
                    (WorkerRegistryEntity r) -> Optional.ofNullable(r.currentLoad()).orElse(0))
                .thenComparing(
                    WorkerRegistryEntity::heartbeatAt,
                    Comparator.nullsLast(Comparator.reverseOrder())))
        .orElse(null);
  }

  /** V87 反压: 已 fully loaded 的 worker 不再被选中. NULL max_concurrent (老数据回退) 视为无上限通过. */
  private static boolean hasCapacity(WorkerRegistryEntity r) {
    Integer max = r.maxConcurrent();
    if (max == null || max <= 0) {
      return true; // 没配置 = 无上限 (向后兼容)
    }
    int current = Optional.ofNullable(r.currentLoad()).orElse(0);
    return current < max;
  }

  private void incrementNoMatchCounter(
      ResourceSchedulingRequest request, ResourceQueueEntity queue, String reason) {
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (registry == null) {
      return;
    }
    Counter.builder(METRIC_NO_MATCH)
        .tags(
            Tags.of(
                "tenantId",
                String.valueOf(request.getTenantId()),
                "workerType",
                String.valueOf(request.getWorkerType()),
                "resourceTag",
                queue == null || queue.resourceTag() == null ? "none" : queue.resourceTag(),
                "reason",
                reason))
        .register(registry)
        .increment();
  }

  private boolean matchesResourceTag(WorkerRegistryEntity candidate, ResourceQueueEntity queue) {
    if (queue == null || !Texts.hasText(queue.resourceTag())) {
      return true;
    }
    String required = queue.resourceTag();
    if (required.equalsIgnoreCase(candidate.resourceTag())) {
      return true;
    }
    return capabilityTagsContain(candidate.capabilityTags(), required);
  }

  private boolean capabilityTagsContain(JsonbString tags, String required) {
    if (tags == null || !Texts.hasText(tags.getValue())) {
      return false;
    }
    String[] parsed;
    try {
      parsed = JsonUtils.fromJson(tags.getValue(), String[].class);
    } catch (RuntimeException ex) {
      // capability_tags 约定是 JSON 数组；非数组或畸形 JSON 视为无能力，不让畸形数据阻塞 selector。
      log.warn("invalid capability_tags JSON on worker: {}", tags.getValue(), ex);
      return false;
    }
    if (parsed == null) {
      return false;
    }
    for (String tag : parsed) {
      if (tag != null && required.equalsIgnoreCase(tag)) {
        return true;
      }
    }
    return false;
  }

  private String resolveWorkerGroup(ResourceSchedulingRequest request, ResourceQueueEntity queue) {
    if (request != null && Texts.hasText(request.getWorkerGroup())) {
      return request.getWorkerGroup();
    }
    return queue == null ? null : queue.workerGroup();
  }
}
