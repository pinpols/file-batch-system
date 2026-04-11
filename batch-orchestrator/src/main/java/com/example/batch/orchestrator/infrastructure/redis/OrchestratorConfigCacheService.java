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

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

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
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(jobCode)) {
            return null;
        }
        String key = BatchRedisKeys.config(tenantId, "job-definition", jobCode);
        JobDefinitionRecord cached = redis.getJson(key, JobDefinitionRecord.class);
        if (cached != null) {
            return cached;
        }
        JobDefinitionRecord loaded =
                jobDefinitionRepository.findFirstByTenantIdAndJobCodeAndEnabled(
                        tenantId, jobCode, true);
        if (loaded != null) {
            redis.setJson(key, loaded, CONFIG_CACHE_TTL);
        }
        return loaded;
    }

    public WorkflowDefinitionRecord findEnabledWorkflowDefinition(
            String tenantId, String workflowCode) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(workflowCode)) {
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

    public BusinessCalendarRecord findEnabledBusinessCalendar(
            String tenantId, String calendarCode) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(calendarCode)) {
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
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(windowCode)) {
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
        if (!StringUtils.hasText(tenantId)) {
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
        if (!StringUtils.hasText(tenantId)) {
            return;
        }
        redis.delete(BatchRedisKeys.config(tenantId, "tenant-quota-policy", "enabled-first"));
    }

    private void evictConfig(String tenantId, String type, String code) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(code)) {
            return;
        }
        redis.delete(BatchRedisKeys.config(tenantId, type, code));
    }
}
