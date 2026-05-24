package com.example.batch.orchestrator.infrastructure.redis;

import com.example.batch.common.redis.BatchRedisKeys;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.domain.entity.BatchWindowEntity;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.orchestrator.mapper.BatchWindowMapper;
import com.example.batch.orchestrator.mapper.BusinessCalendarMapper;
import com.example.batch.orchestrator.mapper.JobDefinitionMapper;
import com.example.batch.orchestrator.mapper.TenantQuotaPolicyMapper;
import com.example.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Orchestrator 配置缓存服务。
 *
 * <p>为作业定义、工作流定义、业务日历、批次窗口、租户配额策略提供统一的 Redis 二级缓存， 缓存 TTL 固定为 5 分钟（{@code CONFIG_CACHE_TTL}）。读取时先查
 * Redis，未命中再查数据库 并回填缓存；仅缓存已启用（{@code enabled=true}）的记录。提供对应的 {@code evict*} 方法
 * 供配置变更时主动失效缓存，防止脏读。所有方法在入参为空时快速返回 {@code null}，不访问缓存。
 *
 * <p>P1 迁移：workflow / business_calendar / batch_window 改走 MyBatis Mapper； job_definition /
 * tenant_quota_policy 留在 Spring Data JDBC 仓库（P2 计划）。
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

  private final OrchestratorRedisSupport redis;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final BatchWindowMapper batchWindowMapper;
  private final TenantQuotaPolicyMapper tenantQuotaPolicyMapper;

  @Autowired
  public OrchestratorConfigCacheService(
      OrchestratorRedisSupport redis,
      JobDefinitionMapper jobDefinitionMapper,
      WorkflowDefinitionMapper workflowDefinitionMapper,
      BusinessCalendarMapper businessCalendarMapper,
      BatchWindowMapper batchWindowMapper,
      TenantQuotaPolicyMapper tenantQuotaPolicyMapper,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.redis = redis;
    this.jobDefinitionMapper = jobDefinitionMapper;
    this.workflowDefinitionMapper = workflowDefinitionMapper;
    this.businessCalendarMapper = businessCalendarMapper;
    this.batchWindowMapper = batchWindowMapper;
    this.tenantQuotaPolicyMapper = tenantQuotaPolicyMapper;
    this.negativeCache =
        Caffeine.newBuilder()
            .expireAfterWrite(NEGATIVE_TTL)
            .maximumSize(NEGATIVE_CACHE_MAX)
            .build();
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (registry != null) {
      registry.gauge(
          "batch.orchestrator.config.negative_cache.size", negativeCache, Cache::estimatedSize);
    }
  }

  // 兼容旧构造器签名 (测试 / 其它注入路径);ObjectProvider 默认 null 即不挂 gauge。
  public OrchestratorConfigCacheService(
      OrchestratorRedisSupport redis,
      JobDefinitionMapper jobDefinitionMapper,
      WorkflowDefinitionMapper workflowDefinitionMapper,
      BusinessCalendarMapper businessCalendarMapper,
      BatchWindowMapper batchWindowMapper,
      TenantQuotaPolicyMapper tenantQuotaPolicyMapper) {
    this(
        redis,
        jobDefinitionMapper,
        workflowDefinitionMapper,
        businessCalendarMapper,
        batchWindowMapper,
        tenantQuotaPolicyMapper,
        nullObjectProvider());
  }

  private static <T> ObjectProvider<T> nullObjectProvider() {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return null;
      }

      @Override
      public T getObject(Object... args) {
        return null;
      }

      @Override
      public T getIfAvailable() {
        return null;
      }

      @Override
      public T getIfUnique() {
        return null;
      }
    };
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
    JobDefinitionEntity cached = redis.getJson(key, JobDefinitionEntity.class);
    if (cached != null) {
      return cached;
    }
    if (isNegativeCached(key)) {
      return null;
    }
    JobDefinitionEntity loaded =
        jobDefinitionMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, jobCode, true);
    if (loaded != null) {
      redis.setJson(key, loaded, CONFIG_CACHE_TTL);
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
    WorkflowDefinitionEntity cached = redis.getJson(key, WorkflowDefinitionEntity.class);
    if (cached != null) {
      return cached;
    }
    if (isNegativeCached(key)) {
      return null;
    }
    WorkflowDefinitionEntity loaded =
        workflowDefinitionMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, workflowCode, true);
    if (loaded != null) {
      redis.setJson(key, loaded, CONFIG_CACHE_TTL);
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
    BusinessCalendarEntity loaded =
        businessCalendarMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, calendarCode, true);
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
    BatchWindowEntity loaded =
        batchWindowMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, windowCode, true);
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
    TenantQuotaPolicyEntity cached = redis.getJson(key, TenantQuotaPolicyEntity.class);
    if (cached != null) {
      return cached;
    }
    if (isNegativeCached(key)) {
      return null;
    }
    TenantQuotaPolicyEntity loaded =
        tenantQuotaPolicyMapper.selectFirstEnabledByTenantId(tenantId, true);
    if (loaded != null) {
      redis.setJson(key, loaded, CONFIG_CACHE_TTL);
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
    redis.delete(BatchRedisKeys.config(tenantId, "tenant-quota-policy", "enabled-first"));
  }

  private void evictConfig(String tenantId, String type, String code) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(code)) {
      return;
    }
    String key = BatchRedisKeys.config(tenantId, type, code);
    redis.delete(key);
    // R3-P2-9：失效 positive cache 时也清 negative，避免开启 disabled 配置时仍命中"已知缺失"残留
    negativeCache.invalidate(key);
  }
}
