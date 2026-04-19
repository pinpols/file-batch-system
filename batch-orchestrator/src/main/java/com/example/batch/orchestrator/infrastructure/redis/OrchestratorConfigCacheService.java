package com.example.batch.orchestrator.infrastructure.redis;

import com.example.batch.common.redis.BatchRedisKeys;
import com.example.batch.orchestrator.domain.entity.BatchWindowRecord;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarRecord;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyRecord;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionRecord;
import com.example.batch.orchestrator.repository.BatchWindowRepository;
import com.example.batch.orchestrator.repository.BusinessCalendarRepository;
import com.example.batch.orchestrator.repository.JobDefinitionRepository;
import com.example.batch.orchestrator.repository.TenantQuotaPolicyRepository;
import com.example.batch.orchestrator.repository.WorkflowDefinitionRepository;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.example.batch.common.utils.Texts;

/**
 * Orchestrator 配置缓存服务。
 *
 * <p>为作业定义、工作流定义、业务日历、批次窗口、租户配额策略提供统一的 Redis 二级缓存，
 * 缓存 TTL 固定为 5 分钟（{@code CONFIG_CACHE_TTL}）。读取时先查 Redis，未命中再查数据库
 * 并回填缓存；仅缓存已启用（{@code enabled=true}）的记录。提供对应的 {@code evict*} 方法
 * 供配置变更时主动失效缓存，防止脏读。所有方法在入参为空时快速返回 {@code null}，不访问缓存。
 */
@Service
@RequiredArgsConstructor
public class OrchestratorConfigCacheService {

  private static final Duration CONFIG_CACHE_TTL = Duration.ofMinutes(5);

  private final OrchestratorRedisSupport redis;
  private final JobDefinitionRepository jobDefinitionRepository;
  private final WorkflowDefinitionRepository workflowDefinitionRepository;
  private final BusinessCalendarRepository businessCalendarRepository;
  private final BatchWindowRepository batchWindowRepository;
  private final TenantQuotaPolicyRepository tenantQuotaPolicyRepository;

  public JobDefinitionRecord findEnabledJobDefinition(String tenantId, String jobCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(jobCode)) {
      return null;
    }
    String key = BatchRedisKeys.config(tenantId, "job-definition", jobCode);
    JobDefinitionRecord cached = redis.getJson(key, JobDefinitionRecord.class);
    if (cached != null) {
      return cached;
    }
    JobDefinitionRecord loaded =
        jobDefinitionRepository.findFirstByTenantIdAndJobCodeAndEnabled(tenantId, jobCode, true);
    if (loaded != null) {
      redis.setJson(key, loaded, CONFIG_CACHE_TTL);
    }
    return loaded;
  }

  public WorkflowDefinitionRecord findEnabledWorkflowDefinition(
      String tenantId, String workflowCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(workflowCode)) {
      return null;
    }
    String key = BatchRedisKeys.config(tenantId, "workflow-definition", workflowCode);
    WorkflowDefinitionRecord cached = redis.getJson(key, WorkflowDefinitionRecord.class);
    if (cached != null) {
      return cached;
    }
    WorkflowDefinitionRecord loaded =
        workflowDefinitionRepository.findFirstByTenantIdAndWorkflowCodeAndEnabled(
            tenantId, workflowCode, true);
    if (loaded != null) {
      redis.setJson(key, loaded, CONFIG_CACHE_TTL);
    }
    return loaded;
  }

  public BusinessCalendarRecord findEnabledBusinessCalendar(String tenantId, String calendarCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(calendarCode)) {
      return null;
    }
    String key = BatchRedisKeys.config(tenantId, "business-calendar", calendarCode);
    BusinessCalendarRecord cached = redis.getJson(key, BusinessCalendarRecord.class);
    if (cached != null) {
      return cached;
    }
    BusinessCalendarRecord loaded =
        businessCalendarRepository.findFirstByTenantIdAndCalendarCodeAndEnabled(
            tenantId, calendarCode, true);
    if (loaded != null) {
      redis.setJson(key, loaded, CONFIG_CACHE_TTL);
    }
    return loaded;
  }

  public BatchWindowRecord findEnabledBatchWindow(String tenantId, String windowCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(windowCode)) {
      return null;
    }
    String key = BatchRedisKeys.config(tenantId, "batch-window", windowCode);
    BatchWindowRecord cached = redis.getJson(key, BatchWindowRecord.class);
    if (cached != null) {
      return cached;
    }
    BatchWindowRecord loaded =
        batchWindowRepository
            .findFirstByTenantIdAndWindowCodeAndEnabled(tenantId, windowCode, true)
            .orElse(null);
    if (loaded != null) {
      redis.setJson(key, loaded, CONFIG_CACHE_TTL);
    }
    return loaded;
  }

  public TenantQuotaPolicyRecord findEnabledQuotaPolicy(String tenantId) {
    if (!Texts.hasText(tenantId)) {
      return null;
    }
    String key = BatchRedisKeys.config(tenantId, "tenant-quota-policy", "enabled-first");
    TenantQuotaPolicyRecord cached = redis.getJson(key, TenantQuotaPolicyRecord.class);
    if (cached != null) {
      return cached;
    }
    TenantQuotaPolicyRecord loaded =
        tenantQuotaPolicyRepository.findFirstEnabledByTenantId(tenantId, true).orElse(null);
    if (loaded != null) {
      redis.setJson(key, loaded, CONFIG_CACHE_TTL);
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
    redis.delete(BatchRedisKeys.config(tenantId, type, code));
  }
}
