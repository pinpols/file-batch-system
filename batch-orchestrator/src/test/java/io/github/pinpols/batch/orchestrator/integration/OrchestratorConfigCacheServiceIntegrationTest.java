package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchClockConfig;
import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.orchestrator.config.OrchestratorConfigCacheProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigMappers;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import io.github.pinpols.batch.orchestrator.mapper.*;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@DisplayName("配置缓存旁路读写:未命中回源并写入 Redis,命中时跳过仓储,失效后重新回源")
class OrchestratorConfigCacheServiceIntegrationTest extends AbstractIntegrationTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableConfigurationProperties(OrchestratorConfigCacheProperties.class)
  @Import({
    BatchClockConfig.class,
    OrchestratorRedisSupport.class,
    OrchestratorConfigMappers.class,
    OrchestratorConfigCacheService.class
  })
  static class TestApplication {}

  @MockitoBean
  private JobDefinitionMapper jobDefinitionMapper;

  @MockitoBean
  private WorkflowDefinitionMapper workflowDefinitionMapper;

  @MockitoBean
  private BusinessCalendarMapper businessCalendarMapper;

  @MockitoBean
  private BatchWindowMapper batchWindowMapper;

  @MockitoBean
  private TenantQuotaPolicyMapper tenantQuotaPolicyMapper;

  @Autowired
  private OrchestratorConfigCacheService configCacheService;

  @Autowired
  private StringRedisTemplate redisTemplate;

  @Test
  @DisplayName("缓存未命中时从仓储加载配置并写入 Redis,返回值与仓储记录一致")
  void shouldLoadFromRepositoryAndPopulateRedis_whenCacheMisses() {
    String tenantId = "t-cache-" + System.nanoTime();
    String jobCode = "JOB-" + System.nanoTime();
    JobDefinitionEntity jobRecord = jobDefinitionRecord(tenantId, jobCode);
    when(jobDefinitionMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, jobCode, true))
        .thenReturn(jobRecord);

    JobDefinitionEntity result = configCacheService.findEnabledJobDefinition(tenantId, jobCode);

    assertThat(result).isNotNull();
    assertThat(result.jobCode()).isEqualTo(jobCode);
    String redisKey = BatchRedisKeys.config(tenantId, "job-definition", jobCode);
    assertThat(redisTemplate.hasKey(redisKey)).isTrue();
  }

  @Test
  @DisplayName("缓存命中时直接返回缓存值,对仓储只查询一次")
  void shouldSkipRepository_whenCacheHits() {
    String tenantId = "t-hit-" + System.nanoTime();
    String jobCode = "JOB-HIT-" + System.nanoTime();
    JobDefinitionEntity jobRecord = jobDefinitionRecord(tenantId, jobCode);
    when(jobDefinitionMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, jobCode, true))
        .thenReturn(jobRecord);

    configCacheService.findEnabledJobDefinition(tenantId, jobCode); // cache miss — populates
    configCacheService.findEnabledJobDefinition(tenantId, jobCode); // cache hit — skips repo

    verify(jobDefinitionMapper, times(1))
        .selectFirstByTenantAndCodeAndEnabled(tenantId, jobCode, true);
  }

  @Test
  @DisplayName("配置失效后 Redis 键被清除,再次读取回源仓储并累计两次查询")
  void shouldClearRedisKeyAndReloadFromRepository_whenEvicted() {
    String tenantId = "t-evict-" + System.nanoTime();
    String jobCode = "JOB-EVICT-" + System.nanoTime();
    JobDefinitionEntity jobRecord = jobDefinitionRecord(tenantId, jobCode);
    when(jobDefinitionMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, jobCode, true))
        .thenReturn(jobRecord);

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
