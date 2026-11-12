package io.github.pinpols.batch.orchestrator.infrastructure.redis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.config.OrchestratorConfigCacheProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchWindowEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.domain.view.JobDefinitionView;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowDefinitionView;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.sql.Connection;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * 共享缓存键包含租户类型通配版本和配置项版本；对应版本成功推进后，迟到回填只会写入旧版本键。
 * 显式固定快照事务直接访问数据库；其他活跃事务可读取已有缓存，但不把本事务回源结果写入缓存。
 */
@Service
public class OrchestratorConfigCacheService {

  private static final Duration CONFIG_CACHE_TTL = Duration.ofMinutes(5);
  private static final String JOB_DEFINITION_CACHE_TYPE = "job-definition";
  private static final String WORKFLOW_DEFINITION_CACHE_TYPE = "workflow-definition";
  private static final String BUSINESS_CALENDAR_CACHE_TYPE = "business-calendar";
  private static final String BATCH_WINDOW_CACHE_TYPE = "batch-window";
  private static final String TENANT_QUOTA_POLICY_CACHE_TYPE = "tenant-quota-policy";
  private static final String QUOTA_POLICY_CACHE_CODE = "enabled-first";
  // R3-P2-9 / S1-3：DB 也返回 null 时（配置被 disabled / 不存在）记录"已知缺失"标记，
  // 在 NEGATIVE_TTL 内直接返回 null，不再 hit DB。scheduler 每秒 tick 配置 disabled 不再
  // 把 DB 打满。本地 cache 即可（每个 orchestrator 实例独立，命中率自然降低也是可接受降级）。
  private static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);
  // P1: Caffeine 替换原 ConcurrentHashMap + 手动容量保护逻辑;maximumSize 由 Caffeine 用
  // window-TinyLFU 自动驱逐 LRU 条目,不再"上限到达 → 整体 clear"造成的 thrashing。
  private static final long NEGATIVE_CACHE_MAX = 10_000L;

  private final Cache<String, String> negativeCache;
  private final Cache<String, JobDefinitionView> jobDefinitionLocalCache;
  private final Cache<String, WorkflowDefinitionView> workflowDefinitionLocalCache;
  private final Cache<String, WorkflowDefinitionView> singleJobWorkflowLocalCache;
  private final Cache<String, TenantQuotaPolicyEntity> quotaPolicyLocalCache;
  private final Map<String, Cache<String, ?>> localPositiveCachesByType;

  private final OrchestratorRedisSupport redis;
  private final OrchestratorConfigMappers configMappers;
  private volatile long localInvalidationEpoch;

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
    this.singleJobWorkflowLocalCache = Caffeine.newBuilder()
        .expireAfterWrite(localPositiveTtl)
        .maximumSize(properties.getLocalPositiveMaximumSize())
        .recordStats()
        .build();
    this.quotaPolicyLocalCache = Caffeine.newBuilder()
        .expireAfterWrite(localPositiveTtl)
        .maximumSize(properties.getLocalPositiveMaximumSize())
        .recordStats()
        .build();
    this.localPositiveCachesByType = Map.of(
        JOB_DEFINITION_CACHE_TYPE, jobDefinitionLocalCache,
        WORKFLOW_DEFINITION_CACHE_TYPE, workflowDefinitionLocalCache,
        TENANT_QUOTA_POLICY_CACHE_TYPE, quotaPolicyLocalCache);
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

  private boolean isNegativeCached(String key, String cacheKey) {
    return cacheKey.equals(negativeCache.getIfPresent(key));
  }

  private synchronized <T> void putLocalIfUnchanged(
      Cache<String, T> cache, String key, T value, long epoch) {
    if (localInvalidationEpoch == epoch) {
      cache.put(key, value);
    }
  }

  private String versionedCacheKey(String tenantId, String type, String code) {
    String versions = redis.configInvalidationVersions(tenantId, type, code);
    String key = BatchRedisKeys.config(tenantId, type, code);
    return EmptyChecks.isNull(versions) ? null : key + ":v2:" + versions;
  }

  private boolean usesFixedTransactionSnapshot() {
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    return TransactionSynchronizationManager.isActualTransactionActive()
        && EmptyChecks.isNotNull(isolation)
        && (isolation == Connection.TRANSACTION_REPEATABLE_READ
            || isolation == Connection.TRANSACTION_SERIALIZABLE);
  }

  public JobDefinitionView findEnabledJobDefinition(String tenantId, String jobCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(jobCode)) {
      return null;
    }
    return readThrough(
        new ConfigCacheSlot<>(
            tenantId, JOB_DEFINITION_CACHE_TYPE, jobCode, JobDefinitionView.class),
        jobDefinitionLocalCache,
        () -> configMappers
            .jobDefinition()
            .selectFirstByTenantAndCodeAndEnabled(tenantId, jobCode, true));
  }

  public WorkflowDefinitionView findEnabledWorkflowDefinition(
      String tenantId, String workflowCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(workflowCode)) {
      return null;
    }
    return readThrough(
        new ConfigCacheSlot<>(
            tenantId, WORKFLOW_DEFINITION_CACHE_TYPE, workflowCode, WorkflowDefinitionView.class),
        workflowDefinitionLocalCache,
        () -> configMappers
            .workflowDefinition()
            .selectFirstByTenantAndCodeAndEnabled(tenantId, workflowCode, true));
  }

  /**
   * 按显式关系键解析 Job 关联的受管单作业 Workflow。此查询只使用
   * 有界、短时的本地缓存，使关系变更沿用现有事务提交后
   * Workflow 缓存失效路径，而不引入另一套 Redis 键生命周期。
   */
  public WorkflowDefinitionView findEnabledSingleJobWorkflowDefinition(
      String tenantId, Long jobDefinitionId) {
    if (!Texts.hasText(tenantId) || EmptyChecks.isNull(jobDefinitionId)) {
      return null;
    }
    if (usesFixedTransactionSnapshot()) {
      return configMappers
          .workflowDefinition()
          .selectFirstByTenantAndSingleJobDefinitionIdAndEnabled(tenantId, jobDefinitionId, true);
    }
    String key = tenantId + ":" + jobDefinitionId;
    WorkflowDefinitionView cached = singleJobWorkflowLocalCache.getIfPresent(key);
    if (EmptyChecks.isNotNull(cached)) {
      return cached;
    }
    long epoch = localInvalidationEpoch;
    WorkflowDefinitionView loaded = configMappers
        .workflowDefinition()
        .selectFirstByTenantAndSingleJobDefinitionIdAndEnabled(tenantId, jobDefinitionId, true);
    if (EmptyChecks.isNotNull(loaded)
        && !TransactionSynchronizationManager.isActualTransactionActive()) {
      putLocalIfUnchanged(singleJobWorkflowLocalCache, key, loaded, epoch);
    }
    return loaded;
  }

  public BusinessCalendarEntity findEnabledBusinessCalendar(String tenantId, String calendarCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(calendarCode)) {
      return null;
    }
    return readThrough(
        new ConfigCacheSlot<>(
            tenantId, BUSINESS_CALENDAR_CACHE_TYPE, calendarCode, BusinessCalendarEntity.class),
        null,
        () -> configMappers
            .businessCalendar()
            .selectFirstByTenantAndCodeAndEnabled(tenantId, calendarCode, true));
  }

  public BatchWindowEntity findEnabledBatchWindow(String tenantId, String windowCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(windowCode)) {
      return null;
    }
    return readThrough(
        new ConfigCacheSlot<>(
            tenantId, BATCH_WINDOW_CACHE_TYPE, windowCode, BatchWindowEntity.class),
        null,
        () -> configMappers
            .batchWindow()
            .selectFirstByTenantAndCodeAndEnabled(tenantId, windowCode, true));
  }

  public TenantQuotaPolicyEntity findEnabledQuotaPolicy(String tenantId) {
    if (!Texts.hasText(tenantId)) {
      return null;
    }
    return readThrough(
        new ConfigCacheSlot<>(
            tenantId,
            TENANT_QUOTA_POLICY_CACHE_TYPE,
            QUOTA_POLICY_CACHE_CODE,
            TenantQuotaPolicyEntity.class),
        quotaPolicyLocalCache,
        () -> configMappers.tenantQuotaPolicy().selectFirstEnabledByTenantId(tenantId, true));
  }

  /**
   * 五类配置共用的读穿流程：固定快照事务直读库；否则依次查本地正缓存（可选）、Redis 版本键、负缓存，最后回源。
   * 活跃事务内的回源结果不回填任何缓存；本地回填以进入时的失效 epoch 为准，避免覆盖并发失效。
   *
   * @param localCache 进程内正缓存；日历和窗口不使用本地正缓存，传 {@code null}
   */
  private <T> T readThrough(
      ConfigCacheSlot<T> slot, Cache<String, T> localCache, Supplier<T> loader) {
    if (usesFixedTransactionSnapshot()) {
      return loader.get();
    }
    String key = BatchRedisKeys.config(slot.tenantId(), slot.type(), slot.code());
    if (EmptyChecks.isNotNull(localCache)) {
      T local = localCache.getIfPresent(key);
      if (EmptyChecks.isNotNull(local)) {
        return local;
      }
    }
    long epoch = localInvalidationEpoch;
    String cacheKey = versionedCacheKey(slot.tenantId(), slot.type(), slot.code());
    T cached = EmptyChecks.isNull(cacheKey) ? null : redis.getJson(cacheKey, slot.valueType());
    if (EmptyChecks.isNotNull(cached)) {
      if (EmptyChecks.isNotNull(localCache)) {
        putLocalIfUnchanged(localCache, key, cached, epoch);
      }
      return cached;
    }
    if (EmptyChecks.isNotNull(cacheKey) && isNegativeCached(key, cacheKey)) {
      return null;
    }
    T loaded = loader.get();
    boolean canCacheResult = EmptyChecks.isNotNull(cacheKey)
        && !TransactionSynchronizationManager.isActualTransactionActive();
    if (canCacheResult && EmptyChecks.isNotNull(loaded)) {
      redis.setJson(cacheKey, loaded, CONFIG_CACHE_TTL);
      if (EmptyChecks.isNotNull(localCache)) {
        putLocalIfUnchanged(localCache, key, loaded, epoch);
      }
    } else if (canCacheResult) {
      putLocalIfUnchanged(negativeCache, key, cacheKey, epoch);
    }
    return loaded;
  }

  /** 一次读穿查询的缓存定位：租户、配置类型、配置编码与 Redis 反序列化类型。 */
  private record ConfigCacheSlot<T>(
      String tenantId, String type, String code, Class<T> valueType) {}

  public void evictJobDefinition(String tenantId, String jobCode) {
    evictConfig(tenantId, JOB_DEFINITION_CACHE_TYPE, jobCode);
  }

  public void evictWorkflowDefinition(String tenantId, String workflowCode) {
    evictConfig(tenantId, WORKFLOW_DEFINITION_CACHE_TYPE, workflowCode);
  }

  public void evictBusinessCalendar(String tenantId, String calendarCode) {
    evictConfig(tenantId, BUSINESS_CALENDAR_CACHE_TYPE, calendarCode);
  }

  public void evictBatchWindow(String tenantId, String windowCode) {
    evictConfig(tenantId, BATCH_WINDOW_CACHE_TYPE, windowCode);
  }

  public void evictQuotaPolicies(String tenantId) {
    if (!Texts.hasText(tenantId)) {
      return;
    }
    String key =
        BatchRedisKeys.config(tenantId, TENANT_QUOTA_POLICY_CACHE_TYPE, QUOTA_POLICY_CACHE_CODE);
    String oldVersionedKey =
        versionedCacheKey(tenantId, TENANT_QUOTA_POLICY_CACHE_TYPE, QUOTA_POLICY_CACHE_CODE);
    redis.advanceConfigInvalidationRevision(
        tenantId, TENANT_QUOTA_POLICY_CACHE_TYPE, QUOTA_POLICY_CACHE_CODE);
    evictLocalByKey(TENANT_QUOTA_POLICY_CACHE_TYPE, key);
    redis.delete(key);
    if (EmptyChecks.isNotNull(oldVersionedKey)) {
      redis.delete(oldVersionedKey);
    }
  }

  public void evictLocal(String tenantId, String type, String code) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(type) || !Texts.hasText(code)) {
      return;
    }
    if ("*".equals(code)) {
      evictLocalByType(type);
      return;
    }
    evictLocalByKey(type, BatchRedisKeys.config(tenantId, type, code));
  }

  public synchronized void evictAllLocal() {
    localInvalidationEpoch++;
    jobDefinitionLocalCache.invalidateAll();
    workflowDefinitionLocalCache.invalidateAll();
    singleJobWorkflowLocalCache.invalidateAll();
    quotaPolicyLocalCache.invalidateAll();
    negativeCache.invalidateAll();
  }

  private void evictConfig(String tenantId, String type, String code) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(code)) {
      return;
    }
    String key = BatchRedisKeys.config(tenantId, type, code);
    String oldVersionedKey = versionedCacheKey(tenantId, type, code);
    redis.advanceConfigInvalidationRevision(tenantId, type, code);
    evictLocalByKey(type, key);
    redis.delete(key);
    if (EmptyChecks.isNotNull(oldVersionedKey)) {
      redis.delete(oldVersionedKey);
    }
  }

  private synchronized void evictLocalByType(String type) {
    localInvalidationEpoch++;
    Cache<String, ?> localCache = localPositiveCachesByType.get(type);
    if (EmptyChecks.isNotNull(localCache)) {
      localCache.invalidateAll();
    }
    invalidateSingleJobWorkflowsIfWorkflow(type);
    negativeCache.invalidateAll();
  }

  private synchronized void evictLocalByKey(String type, String key) {
    localInvalidationEpoch++;
    Cache<String, ?> localCache = localPositiveCachesByType.get(type);
    if (EmptyChecks.isNotNull(localCache)) {
      localCache.invalidate(key);
    }
    invalidateSingleJobWorkflowsIfWorkflow(type);
    // R3-P2-9：失效 positive cache 时也清 negative，避免开启 disabled 配置时仍命中"已知缺失"残留
    negativeCache.invalidate(key);
  }

  /** 单作业 Workflow 关系缓存以 jobDefinitionId 为键，无法按 workflowCode 精确定位，工作流变更时整体失效。 */
  private void invalidateSingleJobWorkflowsIfWorkflow(String type) {
    if (WORKFLOW_DEFINITION_CACHE_TYPE.equals(type)) {
      singleJobWorkflowLocalCache.invalidateAll();
    }
  }
}
