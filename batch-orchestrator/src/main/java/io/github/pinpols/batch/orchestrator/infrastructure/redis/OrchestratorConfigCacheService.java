package io.github.pinpols.batch.orchestrator.infrastructure.redis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.config.OrchestratorConfigCacheProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchWindowEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Orchestrator 配置缓存服务。
 *
 * <p>为作业定义、工作流定义、业务日历、批次窗口、租户配额策略提供统一的 Redis 二级缓存， 缓存 TTL 固定为 5 分钟（{@code CONFIG_CACHE_TTL}）。作业与工作流定义额外使用
 * 250ms 量级的进程内正缓存；租户配额策略同样使用该短缓存，因为一次资源调度会分别执行 job 与 partition
 * 闸门，不能为同一策略重复访问 Redis 和反序列化。短 TTL 将跨进程配置变更的最大陈旧窗口限制在亚秒级。仅缓存已启用记录，并由
 * {@code evict*} 同时失效本地与 Redis 缓存。
 *
 * <p>五类配置均通过 {@link OrchestratorConfigMappers} 中的 MyBatis mapper 读取，缓存服务只负责缓存层级、TTL
 * 和失效语义。
 */
@Service
public class OrchestratorConfigCacheService {

  private static final Duration CONFIG_CACHE_TTL = Duration.ofMinutes(5);
  // R3-P2-9 / S1-3：DB 也返回 null 时（配置被 disabled / 不存在）记录"已知缺失"标记，
  // 在 NEGATIVE_TTL 内直接返回 null，不再 hit DB。scheduler 每秒 tick 配置 disabled 不再
  // 把 DB 打满。本地 cache 即可（每个 orchestrator 实例独立，命中率自然降低也是可接受降级）。
  private static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);
  // P1: Caffeine 替换原 ConcurrentHashMap + 手动容量保护逻辑;maximumSize 由 Caffeine 用
  // window-TinyLFU 自动驱逐 LRU 条目,不再"上限到达 → 整体 clear"造成的 thrashing。
  private static final long NEGATIVE_CACHE_MAX = 10_000L;

  private final Cache<String, Boolean> negativeCache;
  private final Cache<String, JobDefinitionEntity> jobDefinitionLocalCache;
  private final Cache<String, WorkflowDefinitionEntity> workflowDefinitionLocalCache;
  private final Cache<String, TenantQuotaPolicyEntity> quotaPolicyLocalCache;

  private final OrchestratorRedisSupport redis;
  private final OrchestratorConfigMappers configMappers;

  @Autowired
  public OrchestratorConfigCacheService(
      OrchestratorRedisSupport redis,
      OrchestratorConfigMappers configMappers,
      OrchestratorConfigCacheProperties properties,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this(redis, configMappers, properties, meterRegistryProvider.getIfAvailable());
  }

  OrchestratorConfigCacheService(
      OrchestratorRedisSupport redis, OrchestratorConfigMappers configMappers) {
    this(redis, configMappers, new OrchestratorConfigCacheProperties(), (MeterRegistry) null);
  }

  private OrchestratorConfigCacheService(
      OrchestratorRedisSupport redis,
      OrchestratorConfigMappers configMappers,
      OrchestratorConfigCacheProperties properties,
      MeterRegistry meterRegistry) {
    this.redis = redis;
    this.configMappers = configMappers;
    this.negativeCache = Caffeine.newBuilder()
        .expireAfterWrite(NEGATIVE_TTL)
        .maximumSize(NEGATIVE_CACHE_MAX)
        .build();
    Duration localPositiveTtl = Duration.ofMillis(properties.getLocalPositiveTtlMillis());
    this.jobDefinitionLocalCache = Caffeine.newBuilder()
        .expireAfterWrite(localPositiveTtl)
        .maximumSize(properties.getLocalPositiveMaximumSize())
        .recordStats()
        .build();
    this.workflowDefinitionLocalCache = Caffeine.newBuilder()
        .expireAfterWrite(localPositiveTtl)
        .maximumSize(properties.getLocalPositiveMaximumSize())
        .recordStats()
        .build();
    this.quotaPolicyLocalCache = Caffeine.newBuilder()
        .expireAfterWrite(localPositiveTtl)
        .maximumSize(properties.getLocalPositiveMaximumSize())
        .recordStats()
        .build();
    if (meterRegistry != null) {
      meterRegistry.gauge(
          "batch.orchestrator.config.negative_cache.size", negativeCache, Cache::estimatedSize);
      CaffeineCacheMetrics.monitor(
          meterRegistry, jobDefinitionLocalCache, "orchestrator.config.job-definition.local");
      CaffeineCacheMetrics.monitor(
          meterRegistry,
          workflowDefinitionLocalCache,
          "orchestrator.config.workflow-definition.local");
      CaffeineCacheMetrics.monitor(
          meterRegistry, quotaPolicyLocalCache, "orchestrator.config.quota-policy.local");
    }
  }

  private boolean isNegativeCached(String key) {
    return negativeCache.getIfPresent(key) != null;
  }

  private void markNegative(String key) {
    negativeCache.put(key, Boolean.TRUE);
  }

  public JobDefinitionEntity findEnabledJobDefinition(String tenantId, String jobCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(jobCode)) {
      return null;
    }
    String key = BatchRedisKeys.config(tenantId, "job-definition", jobCode);
    JobDefinitionEntity local = jobDefinitionLocalCache.getIfPresent(key);
    if (local != null) {
      return local;
    }
    JobDefinitionEntity cached = redis.getJson(key, JobDefinitionEntity.class);
    if (cached != null) {
      jobDefinitionLocalCache.put(key, cached);
      return cached;
    }
    if (isNegativeCached(key)) {
      return null;
    }
    JobDefinitionEntity loaded =
        configMappers.jobDefinition().selectFirstByTenantAndCodeAndEnabled(tenantId, jobCode, true);
    if (loaded != null) {
      redis.setJson(key, loaded, CONFIG_CACHE_TTL);
      jobDefinitionLocalCache.put(key, loaded);
    } else {
      markNegative(key);
    }
    return loaded;
  }

  public WorkflowDefinitionEntity findEnabledWorkflowDefinition(
      String tenantId, String workflowCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(workflowCode)) {
      return null;
    }
    String key = BatchRedisKeys.config(tenantId, "workflow-definition", workflowCode);
    WorkflowDefinitionEntity local = workflowDefinitionLocalCache.getIfPresent(key);
    if (local != null) {
      return local;
    }
    WorkflowDefinitionEntity cached = redis.getJson(key, WorkflowDefinitionEntity.class);
    if (cached != null) {
      workflowDefinitionLocalCache.put(key, cached);
      return cached;
    }
    if (isNegativeCached(key)) {
      return null;
    }
    WorkflowDefinitionEntity loaded = configMappers
        .workflowDefinition()
        .selectFirstByTenantAndCodeAndEnabled(tenantId, workflowCode, true);
    if (loaded != null) {
      redis.setJson(key, loaded, CONFIG_CACHE_TTL);
      workflowDefinitionLocalCache.put(key, loaded);
    } else {
      markNegative(key);
    }
    return loaded;
  }

  public BusinessCalendarEntity findEnabledBusinessCalendar(String tenantId, String calendarCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(calendarCode)) {
      return null;
    }
    String key = BatchRedisKeys.config(tenantId, "business-calendar", calendarCode);
    BusinessCalendarEntity cached = redis.getJson(key, BusinessCalendarEntity.class);
    if (cached != null) {
      return cached;
    }
    if (isNegativeCached(key)) {
      return null;
    }
    BusinessCalendarEntity loaded = configMappers
        .businessCalendar()
        .selectFirstByTenantAndCodeAndEnabled(tenantId, calendarCode, true);
    if (loaded != null) {
      redis.setJson(key, loaded, CONFIG_CACHE_TTL);
    } else {
      markNegative(key);
    }
    return loaded;
  }

  public BatchWindowEntity findEnabledBatchWindow(String tenantId, String windowCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(windowCode)) {
      return null;
    }
    String key = BatchRedisKeys.config(tenantId, "batch-window", windowCode);
    BatchWindowEntity cached = redis.getJson(key, BatchWindowEntity.class);
    if (cached != null) {
      return cached;
    }
    if (isNegativeCached(key)) {
      return null;
    }
    BatchWindowEntity loaded = configMappers
        .batchWindow()
        .selectFirstByTenantAndCodeAndEnabled(tenantId, windowCode, true);
    if (loaded != null) {
      redis.setJson(key, loaded, CONFIG_CACHE_TTL);
    } else {
      markNegative(key);
    }
    return loaded;
  }

  public TenantQuotaPolicyEntity findEnabledQuotaPolicy(String tenantId) {
    if (!Texts.hasText(tenantId)) {
      return null;
    }
    String key = BatchRedisKeys.config(tenantId, "tenant-quota-policy", "enabled-first");
    TenantQuotaPolicyEntity local = quotaPolicyLocalCache.getIfPresent(key);
    if (local != null) {
      return local;
    }
    TenantQuotaPolicyEntity cached = redis.getJson(key, TenantQuotaPolicyEntity.class);
    if (cached != null) {
      quotaPolicyLocalCache.put(key, cached);
      return cached;
    }
    if (isNegativeCached(key)) {
      return null;
    }
    TenantQuotaPolicyEntity loaded =
        configMappers.tenantQuotaPolicy().selectFirstEnabledByTenantId(tenantId, true);
    if (loaded != null) {
      redis.setJson(key, loaded, CONFIG_CACHE_TTL);
      quotaPolicyLocalCache.put(key, loaded);
    } else {
      markNegative(key);
    }
    return loaded;
  }

  public void evictJobDefinition(String tenantId, String jobCode) {
    evictConfig(tenantId, "job-definition", jobCode);
  }

  public void evictWorkflowDefinition(String tenantId, String workflowCode) {
    evictConfig(tenantId, "workflow-definition", workflowCode);
  }

  public void evictBusinessCalendar(String tenantId, String calendarCode) {
    evictConfig(tenantId, "business-calendar", calendarCode);
  }

  public void evictBatchWindow(String tenantId, String windowCode) {
    evictConfig(tenantId, "batch-window", windowCode);
  }

  public void evictQuotaPolicies(String tenantId) {
    if (!Texts.hasText(tenantId)) {
      return;
    }
    String key = BatchRedisKeys.config(tenantId, "tenant-quota-policy", "enabled-first");
    quotaPolicyLocalCache.invalidate(key);
    negativeCache.invalidate(key);
    redis.delete(key);
  }

  private void evictConfig(String tenantId, String type, String code) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(code)) {
      return;
    }
    String key = BatchRedisKeys.config(tenantId, type, code);
    if ("job-definition".equals(type)) {
      jobDefinitionLocalCache.invalidate(key);
    } else if ("workflow-definition".equals(type)) {
      workflowDefinitionLocalCache.invalidate(key);
    }
    redis.delete(key);
    // R3-P2-9：失效 positive cache 时也清 negative，避免开启 disabled 配置时仍命中"已知缺失"残留
    negativeCache.invalidate(key);
  }
}
