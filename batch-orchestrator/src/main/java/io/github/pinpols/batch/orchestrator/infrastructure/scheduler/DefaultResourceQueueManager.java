package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.scheduler.ResourceQueueManager;
import io.github.pinpols.batch.orchestrator.config.OrchestratorConfigCacheProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.ResourceQueueEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.mapper.ResourceQueueMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 解析调度请求的目标资源队列：有显式 {@code queueCode} 时直查，否则在租户启用的所有队列里按下述优先级挑一个：
 *
 * <ol>
 *   <li>{@code queueType} 精确匹配 {@code workerType} 的队列优先于 {@code MIXED} 队列（避免"混合队列"抢走本该去专用队列的作业）。
 *   <li>{@code fairShareWeight} 倒序——权重大的队列更可能被选中。
 *   <li>{@code maxRunningJobs} 倒序——容量大的队列优先。
 *   <li>{@code maxRunningPartitions} 倒序——分区容量大的优先。
 *   <li>{@code queueCode} 字典序回退，保证挑选结果稳定（防并发下随机性）。
 * </ol>
 *
 * <p>资源队列属于低频变更配置。进程内缓存只保留一个亚秒级窗口，同时缓存“租户没有启用队列”的空结果，避免 launch
 * 洪峰对同一租户重复查询；跨实例配置变更最迟在 TTL 结束后生效。{@code fairShareWeight / maxRunningJobs /
 * maxRunningPartitions} 的 null / ≤0 值统一规范化为 1。
 */
@Component
public class DefaultResourceQueueManager implements ResourceQueueManager {

  private final ResourceQueueMapper resourceQueueMapper;
  private final Cache<String, List<ResourceQueueEntity>> enabledQueuesByTenant;

  @Autowired
  public DefaultResourceQueueManager(
      ResourceQueueMapper resourceQueueMapper,
      OrchestratorConfigCacheProperties properties,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this(resourceQueueMapper, properties, meterRegistryProvider.getIfAvailable());
  }

  /** 保留给不启动 Spring 容器的单元测试和轻量调用方，参数默认值与生产配置类一致。 */
  public DefaultResourceQueueManager(ResourceQueueMapper resourceQueueMapper) {
    this(resourceQueueMapper, new OrchestratorConfigCacheProperties(), (MeterRegistry) null);
  }

  private DefaultResourceQueueManager(
      ResourceQueueMapper resourceQueueMapper,
      OrchestratorConfigCacheProperties properties,
      MeterRegistry meterRegistry) {
    this.resourceQueueMapper = resourceQueueMapper;
    this.enabledQueuesByTenant = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMillis(properties.getLocalPositiveTtlMillis()))
        .maximumSize(properties.getLocalPositiveMaximumSize())
        .recordStats()
        .build();
    if (meterRegistry != null) {
      CaffeineCacheMetrics.monitor(
          meterRegistry, enabledQueuesByTenant, "orchestrator.config.resource-queue.local");
    }
  }

  @Override
  public ResourceQueueEntity resolveQueue(ResourceSchedulingRequest request) {
    if (request == null || !Texts.hasText(request.getTenantId())) {
      return null;
    }
    List<ResourceQueueEntity> queues =
        enabledQueuesByTenant.get(request.getTenantId(), this::loadEnabledQueues);
    if (EmptyChecks.isEmpty(queues)) {
      return null;
    }
    if (Texts.hasText(request.getQueueCode())) {
      return queues.stream()
          .filter(queue -> request.getQueueCode().equalsIgnoreCase(queue.queueCode()))
          .findFirst()
          .orElse(null);
    }
    return queues.stream()
        .filter(queue -> matchesQueueType(queue, request.getWorkerType()))
        .sorted(Comparator.comparing(
                (ResourceQueueEntity queue) -> "MIXED".equalsIgnoreCase(queue.queueType()))
            .thenComparing(
                queue -> normalizedWeight(queue.fairShareWeight()), Comparator.reverseOrder())
            .thenComparing(
                queue -> normalizedWeight(queue.maxRunningJobs()), Comparator.reverseOrder())
            .thenComparing(
                queue -> normalizedWeight(queue.maxRunningPartitions()), Comparator.reverseOrder())
            .thenComparing(
                ResourceQueueEntity::queueCode, Comparator.nullsLast(String::compareToIgnoreCase)))
        .findFirst()
        .orElse(null);
  }

  private List<ResourceQueueEntity> loadEnabledQueues(String tenantId) {
    List<ResourceQueueEntity> loaded = resourceQueueMapper.selectByTenantAndEnabled(tenantId, true);
    return EmptyChecks.isEmpty(loaded) ? List.of() : List.copyOf(loaded);
  }

  private boolean matchesQueueType(ResourceQueueEntity queue, String workerType) {
    if (queue == null) {
      return false;
    }
    if (!Texts.hasText(workerType)) {
      return true;
    }
    return workerType.equalsIgnoreCase(queue.queueType())
        || "MIXED".equalsIgnoreCase(queue.queueType());
  }

  private Integer normalizedWeight(Integer weight) {
    return weight == null || weight <= 0 ? 1 : weight;
  }
}
