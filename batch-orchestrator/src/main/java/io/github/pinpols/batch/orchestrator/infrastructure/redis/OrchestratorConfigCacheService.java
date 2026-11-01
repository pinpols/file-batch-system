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
  private static final String WORKFLOW_DEFINITION_CACHE_TYPE = "workflow-definition";
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
    return versions == null ? null : key + ":v2:" + versions;
  }

  private boolean usesFixedTransactionSnapshot() {
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    return TransactionSynchronizationManager.isActualTransactionActive()
        && isolation != null
        && (isolation == Connection.TRANSACTION_REPEATABLE_READ
            || isolation == Connection.TRANSACTION_SERIALIZABLE);
  }

  public JobDefinitionView findEnabledJobDefinition(String tenantId, String jobCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(jobCode)) {
      return null;
    }
    if (usesFixedTransactionSnapshot()) {
      return configMappers
          .jobDefinition()
          .selectFirstByTenantAndCodeAndEnabled(tenantId, jobCode, true);
    }
    String key = BatchRedisKeys.config(tenantId, "job-definition", jobCode);
    JobDefinitionView local = jobDefinitionLocalCache.getIfPresent(key);
    if (local != null) {
      return local;
    }
    long epoch = localInvalidationEpoch;
    String cacheKey = versionedCacheKey(tenantId, "job-definition", jobCode);
    JobDefinitionView cached =
        cacheKey == null ? null : redis.getJson(cacheKey, JobDefinitionView.class);
    if (cached != null) {
      putLocalIfUnchanged(jobDefinitionLocalCache, key, cached, epoch);
      return cached;
    }
    if (cacheKey != null && isNegativeCached(key, cacheKey)) {
      return null;
    }
    JobDefinitionView loaded =
        configMappers.jobDefinition().selectFirstByTenantAndCodeAndEnabled(tenantId, jobCode, true);
    boolean canCacheResult = !TransactionSynchronizationManager.isActualTransactionActive();
    if (cacheKey != null && canCacheResult && loaded != null) {
      redis.setJson(cacheKey, loaded, CONFIG_CACHE_TTL);
      putLocalIfUnchanged(jobDefinitionLocalCache, key, loaded, epoch);
    } else if (cacheKey != null && canCacheResult) {
      putLocalIfUnchanged(negativeCache, key, cacheKey, epoch);
    }
    return loaded;
  }

  public WorkflowDefinitionView findEnabledWorkflowDefinition(
      String tenantId, String workflowCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(workflowCode)) {
      return null;
    }
    if (usesFixedTransactionSnapshot()) {
      return configMappers
          .workflowDefinition()
          .selectFirstByTenantAndCodeAndEnabled(tenantId, workflowCode, true);
    }
    String key = BatchRedisKeys.config(tenantId, WORKFLOW_DEFINITION_CACHE_TYPE, workflowCode);
    WorkflowDefinitionView local = workflowDefinitionLocalCache.getIfPresent(key);
    if (local != null) {
      return local;
    }
    long epoch = localInvalidationEpoch;
    String cacheKey = versionedCacheKey(tenantId, WORKFLOW_DEFINITION_CACHE_TYPE, workflowCode);
    WorkflowDefinitionView cached =
        cacheKey == null ? null : redis.getJson(cacheKey, WorkflowDefinitionView.class);
    if (cached != null) {
      putLocalIfUnchanged(workflowDefinitionLocalCache, key, cached, epoch);
      return cached;
    }
    if (cacheKey != null && isNegativeCached(key, cacheKey)) {
      return null;
    }
    WorkflowDefinitionView loaded = configMappers
        .workflowDefinition()
        .selectFirstByTenantAndCodeAndEnabled(tenantId, workflowCode, true);
    boolean canCacheResult = !TransactionSynchronizationManager.isActualTransactionActive();
    if (cacheKey != null && canCacheResult && loaded != null) {
      redis.setJson(cacheKey, loaded, CONFIG_CACHE_TTL);
      putLocalIfUnchanged(workflowDefinitionLocalCache, key, loaded, epoch);
    } else if (cacheKey != null && canCacheResult) {
      putLocalIfUnchanged(negativeCache, key, cacheKey, epoch);
    }
    return loaded;
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
    if (usesFixedTransactionSnapshot()) {
      return configMappers
          .businessCalendar()
          .selectFirstByTenantAndCodeAndEnabled(tenantId, calendarCode, true);
    }
    String key = BatchRedisKeys.config(tenantId, "business-calendar", calendarCode);
    long epoch = localInvalidationEpoch;
    String cacheKey = versionedCacheKey(tenantId, "business-calendar", calendarCode);
    BusinessCalendarEntity cached =
        cacheKey == null ? null : redis.getJson(cacheKey, BusinessCalendarEntity.class);
    if (cached != null) {
      return cached;
    }
    if (cacheKey != null && isNegativeCached(key, cacheKey)) {
      return null;
    }
    BusinessCalendarEntity loaded = configMappers
        .businessCalendar()
        .selectFirstByTenantAndCodeAndEnabled(tenantId, calendarCode, true);
    boolean canCacheResult = !TransactionSynchronizationManager.isActualTransactionActive();
    if (cacheKey != null && canCacheResult && loaded != null) {
      redis.setJson(cacheKey, loaded, CONFIG_CACHE_TTL);
    } else if (cacheKey != null && canCacheResult) {
      putLocalIfUnchanged(negativeCache, key, cacheKey, epoch);
    }
    return loaded;
  }

  public BatchWindowEntity findEnabledBatchWindow(String tenantId, String windowCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(windowCode)) {
      return null;
    }
    if (usesFixedTransactionSnapshot()) {
      return configMappers
          .batchWindow()
          .selectFirstByTenantAndCodeAndEnabled(tenantId, windowCode, true);
    }
    String key = BatchRedisKeys.config(tenantId, "batch-window", windowCode);
    long epoch = localInvalidationEpoch;
    String cacheKey = versionedCacheKey(tenantId, "batch-window", windowCode);
    BatchWindowEntity cached =
        cacheKey == null ? null : redis.getJson(cacheKey, BatchWindowEntity.class);
    if (cached != null) {
      return cached;
    }
    if (cacheKey != null && isNegativeCached(key, cacheKey)) {
      return null;
    }
    BatchWindowEntity loaded = configMappers
        .batchWindow()
        .selectFirstByTenantAndCodeAndEnabled(tenantId, windowCode, true);
    boolean canCacheResult = !TransactionSynchronizationManager.isActualTransactionActive();
    if (cacheKey != null && canCacheResult && loaded != null) {
      redis.setJson(cacheKey, loaded, CONFIG_CACHE_TTL);
    } else if (cacheKey != null && canCacheResult) {
      putLocalIfUnchanged(negativeCache, key, cacheKey, epoch);
    }
    return loaded;
  }

  public TenantQuotaPolicyEntity findEnabledQuotaPolicy(String tenantId) {
    if (!Texts.hasText(tenantId)) {
      return null;
    }
    if (usesFixedTransactionSnapshot()) {
      return configMappers.tenantQuotaPolicy().selectFirstEnabledByTenantId(tenantId, true);
    }
    String key = BatchRedisKeys.config(tenantId, "tenant-quota-policy", "enabled-first");
    TenantQuotaPolicyEntity local = quotaPolicyLocalCache.getIfPresent(key);
    if (local != null) {
      return local;
    }
    long epoch = localInvalidationEpoch;
    String cacheKey = versionedCacheKey(tenantId, "tenant-quota-policy", "enabled-first");
    TenantQuotaPolicyEntity cached =
        cacheKey == null ? null : redis.getJson(cacheKey, TenantQuotaPolicyEntity.class);
    if (cached != null) {
      putLocalIfUnchanged(quotaPolicyLocalCache, key, cached, epoch);
      return cached;
    }
    if (cacheKey != null && isNegativeCached(key, cacheKey)) {
      return null;
    }
    TenantQuotaPolicyEntity loaded =
        configMappers.tenantQuotaPolicy().selectFirstEnabledByTenantId(tenantId, true);
    boolean canCacheResult = !TransactionSynchronizationManager.isActualTransactionActive();
    if (cacheKey != null && canCacheResult && loaded != null) {
      redis.setJson(cacheKey, loaded, CONFIG_CACHE_TTL);
      putLocalIfUnchanged(quotaPolicyLocalCache, key, loaded, epoch);
    } else if (cacheKey != null && canCacheResult) {
      putLocalIfUnchanged(negativeCache, key, cacheKey, epoch);
    }
    return loaded;
  }

  public void evictJobDefinition(String tenantId, String jobCode) {
    evictConfig(tenantId, "job-definition", jobCode);
  }

  public void evictWorkflowDefinition(String tenantId, String workflowCode) {
    evictConfig(tenantId, WORKFLOW_DEFINITION_CACHE_TYPE, workflowCode);
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
    String oldVersionedKey = versionedCacheKey(tenantId, "tenant-quota-policy", "enabled-first");
    redis.advanceConfigInvalidationRevision(tenantId, "tenant-quota-policy", "enabled-first");
    evictLocalByKey("tenant-quota-policy", key);
    redis.delete(key);
    if (oldVersionedKey != null) {
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
    if (oldVersionedKey != null) {
      redis.delete(oldVersionedKey);
    }
  }

  private synchronized void evictLocalByType(String type) {
    localInvalidationEpoch++;
    if ("job-definition".equals(type)) {
      jobDefinitionLocalCache.invalidateAll();
    } else if (WORKFLOW_DEFINITION_CACHE_TYPE.equals(type)) {
      workflowDefinitionLocalCache.invalidateAll();
      singleJobWorkflowLocalCache.invalidateAll();
    } else if ("tenant-quota-policy".equals(type)) {
      quotaPolicyLocalCache.invalidateAll();
    }
    negativeCache.invalidateAll();
  }

  private synchronized void evictLocalByKey(String type, String key) {
    localInvalidationEpoch++;
    if ("job-definition".equals(type)) {
      jobDefinitionLocalCache.invalidate(key);
    } else if (WORKFLOW_DEFINITION_CACHE_TYPE.equals(type)) {
      workflowDefinitionLocalCache.invalidate(key);
      singleJobWorkflowLocalCache.invalidateAll();
    } else if ("tenant-quota-policy".equals(type)) {
      quotaPolicyLocalCache.invalidate(key);
    }
    // R3-P2-9：失效 positive cache 时也清 negative，避免开启 disabled 配置时仍命中"已知缺失"残留
    negativeCache.invalidate(key);
  }
}
