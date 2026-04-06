package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.redis.BatchRedisKeys;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import com.example.batch.orchestrator.repository.BatchWindowRepository;
import com.example.batch.orchestrator.repository.BusinessCalendarRepository;
import com.example.batch.orchestrator.repository.JobDefinitionRepository;
import com.example.batch.orchestrator.repository.TenantQuotaPolicyRepository;
import com.example.batch.orchestrator.repository.WorkflowDefinitionRepository;
import com.example.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 集成测试：验证 OrchestratorConfigCacheService 的缓存旁路（Cache-Aside）模式
 * 使用真实 Redis 容器，Repository 层使用 Mock。
 */
@SpringBootTest(
        classes = OrchestratorConfigCacheServiceIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrchestratorConfigCacheServiceIntegrationTest extends AbstractIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({OrchestratorRedisSupport.class, OrchestratorConfigCacheService.class})
    static class TestApplication {
    }

    @MockitoBean
    private JobDefinitionRepository jobDefinitionRepository;
    @MockitoBean
    private WorkflowDefinitionRepository workflowDefinitionRepository;
    @MockitoBean
    private BusinessCalendarRepository businessCalendarRepository;
    @MockitoBean
    private BatchWindowRepository batchWindowRepository;
    @MockitoBean
    private TenantQuotaPolicyRepository tenantQuotaPolicyRepository;

    @Autowired
    private OrchestratorConfigCacheService configCacheService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void cacheMissLoadsFromRepositoryAndPopulatesRedis() {
        String tenantId = "t-cache-" + System.nanoTime();
        String jobCode = "JOB-" + System.nanoTime();
        JobDefinitionRecord record = jobDefinitionRecord(tenantId, jobCode);
        when(jobDefinitionRepository.findFirstByTenantIdAndJobCodeAndEnabled(tenantId, jobCode, true))
                .thenReturn(record);

        JobDefinitionRecord result = configCacheService.findEnabledJobDefinition(tenantId, jobCode);

        assertThat(result).isNotNull();
        assertThat(result.jobCode()).isEqualTo(jobCode);
        String redisKey = BatchRedisKeys.config(tenantId, "job-definition", jobCode);
        assertThat(redisTemplate.hasKey(redisKey)).isTrue();
    }

    @Test
    void cacheHitSkipsRepository() {
        String tenantId = "t-hit-" + System.nanoTime();
        String jobCode = "JOB-HIT-" + System.nanoTime();
        JobDefinitionRecord record = jobDefinitionRecord(tenantId, jobCode);
        when(jobDefinitionRepository.findFirstByTenantIdAndJobCodeAndEnabled(tenantId, jobCode, true))
                .thenReturn(record);

        configCacheService.findEnabledJobDefinition(tenantId, jobCode);  // cache miss — populates
        configCacheService.findEnabledJobDefinition(tenantId, jobCode);  // cache hit — skips repo

        verify(jobDefinitionRepository, times(1))
                .findFirstByTenantIdAndJobCodeAndEnabled(tenantId, jobCode, true);
    }

    @Test
    void evictClearsRedisKeyAndNextCallHitsRepository() {
        String tenantId = "t-evict-" + System.nanoTime();
        String jobCode = "JOB-EVICT-" + System.nanoTime();
        JobDefinitionRecord record = jobDefinitionRecord(tenantId, jobCode);
        when(jobDefinitionRepository.findFirstByTenantIdAndJobCodeAndEnabled(tenantId, jobCode, true))
                .thenReturn(record);

        configCacheService.findEnabledJobDefinition(tenantId, jobCode);  // populates cache
        configCacheService.evictJobDefinition(tenantId, jobCode);         // clears cache

        String redisKey = BatchRedisKeys.config(tenantId, "job-definition", jobCode);
        assertThat(redisTemplate.hasKey(redisKey)).isFalse();

        configCacheService.findEnabledJobDefinition(tenantId, jobCode);  // cache miss again

        verify(jobDefinitionRepository, times(2))
                .findFirstByTenantIdAndJobCodeAndEnabled(tenantId, jobCode, true);
    }

    private static JobDefinitionRecord jobDefinitionRecord(String tenantId, String jobCode) {
        return new JobDefinitionRecord(
                1L, tenantId, jobCode, "Test Job", "IMPORT", "BIZ",
                "MANUAL", null, "Asia/Shanghai", "default", "default",
                null, null, "MANUAL", false, null, null, null,
                null, null, null, 5, null, 1, true, null
        );
    }
}
