package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchClockConfig;
import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import io.github.pinpols.batch.orchestrator.mapper.*;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 集成测试：验证 OrchestratorConfigCacheService 的缓存旁路（Cache-Aside）模式 使用真实 Redis 容器，Repository 层使用 Mock。
 */
@SpringBootTest(
    classes = OrchestratorConfigCacheServiceIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"batch.startup-self-check.enabled=false"})
class OrchestratorConfigCacheServiceIntegrationTest extends AbstractIntegrationTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({
    BatchClockConfig.class,
    OrchestratorRedisSupport.class,
    OrchestratorConfigCacheService.class
  })
  static class TestApplication {}

  @MockitoBean private JobDefinitionMapper jobDefinitionMapper;
  @MockitoBean private WorkflowDefinitionMapper workflowDefinitionMapper;
  @MockitoBean private BusinessCalendarMapper businessCalendarMapper;
  @MockitoBean private BatchWindowMapper batchWindowMapper;
  @MockitoBean private TenantQuotaPolicyMapper tenantQuotaPolicyMapper;

  @Autowired private OrchestratorConfigCacheService configCacheService;

  @Autowired private StringRedisTemplate redisTemplate;

  @Test
  void cacheMissLoadsFromRepositoryAndPopulatesRedis() {
    String tenantId = "t-cache-" + System.nanoTime();
    String jobCode = "JOB-" + System.nanoTime();
    JobDefinitionEntity record = jobDefinitionRecord(tenantId, jobCode);
    when(jobDefinitionMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, jobCode, true))
        .thenReturn(record);

    JobDefinitionEntity result = configCacheService.findEnabledJobDefinition(tenantId, jobCode);

    assertThat(result).isNotNull();
    assertThat(result.jobCode()).isEqualTo(jobCode);
    String redisKey = BatchRedisKeys.config(tenantId, "job-definition", jobCode);
    assertThat(redisTemplate.hasKey(redisKey)).isTrue();
  }

  @Test
  void cacheHitSkipsRepository() {
    String tenantId = "t-hit-" + System.nanoTime();
    String jobCode = "JOB-HIT-" + System.nanoTime();
    JobDefinitionEntity record = jobDefinitionRecord(tenantId, jobCode);
    when(jobDefinitionMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, jobCode, true))
        .thenReturn(record);

    configCacheService.findEnabledJobDefinition(tenantId, jobCode); // cache miss — populates
    configCacheService.findEnabledJobDefinition(tenantId, jobCode); // cache hit — skips repo

    verify(jobDefinitionMapper, times(1))
        .selectFirstByTenantAndCodeAndEnabled(tenantId, jobCode, true);
  }

  @Test
  void evictClearsRedisKeyAndNextCallHitsRepository() {
    String tenantId = "t-evict-" + System.nanoTime();
    String jobCode = "JOB-EVICT-" + System.nanoTime();
    JobDefinitionEntity record = jobDefinitionRecord(tenantId, jobCode);
    when(jobDefinitionMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, jobCode, true))
        .thenReturn(record);

    configCacheService.findEnabledJobDefinition(tenantId, jobCode); // populates cache
    configCacheService.evictJobDefinition(tenantId, jobCode); // clears cache

    String redisKey = BatchRedisKeys.config(tenantId, "job-definition", jobCode);
    assertThat(redisTemplate.hasKey(redisKey)).isFalse();

    configCacheService.findEnabledJobDefinition(tenantId, jobCode); // cache miss again

    verify(jobDefinitionMapper, times(2))
        .selectFirstByTenantAndCodeAndEnabled(tenantId, jobCode, true);
  }

  private static JobDefinitionEntity jobDefinitionRecord(String tenantId, String jobCode) {
    return new JobDefinitionEntity(
        1L,
        tenantId,
        jobCode,
        "Test Job",
        "IMPORT",
        "BIZ",
        "MANUAL",
        null,
        "Asia/Shanghai",
        "default",
        "default",
        null,
        null,
        "MANUAL",
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        5,
        null,
        1,
        true,
        null,
        null,
        null);
  }
}
