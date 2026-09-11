package io.github.pinpols.batch.orchestrator.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchWindowMapper;
import io.github.pinpols.batch.orchestrator.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrchestratorConfigCacheServiceTest {

  @Mock
  private OrchestratorRedisSupport redis;

  @Mock
  private JobDefinitionMapper jobDefinitionMapper;

  @Mock
  private WorkflowDefinitionMapper workflowDefinitionMapper;

  @Mock
  private BusinessCalendarMapper businessCalendarMapper;

  @Mock
  private BatchWindowMapper batchWindowMapper;

  @Mock
  private TenantQuotaPolicyMapper tenantQuotaPolicyMapper;

  private OrchestratorConfigCacheService service;

  @BeforeEach
  void setUp() {
    service = new OrchestratorConfigCacheService(
        redis,
        new OrchestratorConfigMappers(
            jobDefinitionMapper,
            workflowDefinitionMapper,
            businessCalendarMapper,
            batchWindowMapper,
            tenantQuotaPolicyMapper));
  }

  @Test
  void nullOrBlankTenantIdReturnsNull() {
    assertThat(service.findEnabledJobDefinition(null, "JOB1")).isNull();
    assertThat(service.findEnabledJobDefinition("", "JOB1")).isNull();
    assertThat(service.findEnabledJobDefinition("  ", "JOB1")).isNull();
    verify(redis, never()).getJson(anyString(), any());
  }

  @Test
  void nullOrBlankJobCodeReturnsNull() {
    assertThat(service.findEnabledJobDefinition("t1", null)).isNull();
    assertThat(service.findEnabledJobDefinition("t1", "")).isNull();
    verify(redis, never()).getJson(anyString(), any());
  }

  @Test
  void cacheHitReturnsValueWithoutCallingRepository() {
    JobDefinitionEntity cached = jobDefinitionRecord("t1", "JOB1");
    when(redis.getJson(anyString(), eq(JobDefinitionEntity.class))).thenReturn(cached);

    JobDefinitionEntity result = service.findEnabledJobDefinition("t1", "JOB1");

    assertThat(result).isSameAs(cached);
    verify(jobDefinitionMapper, never()).selectFirstByTenantAndCodeAndEnabled(any(), any(), any());
    verify(redis, never()).setJson(anyString(), any(), any(Duration.class));
  }

  @Test
  void repeatedJobDefinitionReadUsesLocalPositiveCache() {
    JobDefinitionEntity cached = jobDefinitionRecord("t1", "JOB1");
    when(redis.getJson(anyString(), eq(JobDefinitionEntity.class))).thenReturn(cached);

    assertThat(service.findEnabledJobDefinition("t1", "JOB1")).isSameAs(cached);
    assertThat(service.findEnabledJobDefinition("t1", "JOB1")).isSameAs(cached);

    verify(redis, times(1)).getJson(anyString(), eq(JobDefinitionEntity.class));
  }

  @Test
  void evictClearsLocalPositiveCache() {
    JobDefinitionEntity cached = jobDefinitionRecord("t1", "JOB3");
    when(redis.getJson(anyString(), eq(JobDefinitionEntity.class)))
        .thenReturn(cached)
        .thenReturn(null);

    assertThat(service.findEnabledJobDefinition("t1", "JOB3")).isSameAs(cached);
    service.evictJobDefinition("t1", "JOB3");
    assertThat(service.findEnabledJobDefinition("t1", "JOB3")).isNull();

    verify(redis, times(2)).getJson(anyString(), eq(JobDefinitionEntity.class));
  }

  @Test
  void cacheMissCallsRepositoryAndCachesResult() {
    JobDefinitionEntity fromDb = jobDefinitionRecord("t1", "JOB2");
    when(redis.getJson(anyString(), eq(JobDefinitionEntity.class))).thenReturn(null);
    when(jobDefinitionMapper.selectFirstByTenantAndCodeAndEnabled("t1", "JOB2", true))
        .thenReturn(fromDb);

    JobDefinitionEntity result = service.findEnabledJobDefinition("t1", "JOB2");

    assertThat(result).isSameAs(fromDb);
    verify(redis).setJson(anyString(), eq(fromDb), any(Duration.class));
  }

  @Test
  void evictDeletesRedisKey() {
    service.evictJobDefinition("t1", "JOB3");

    verify(redis).delete("config:t1:job-definition:JOB3");
  }

  @Test
  void repeatedQuotaPolicyReadUsesLocalPositiveCache() {
    TenantQuotaPolicyEntity cached = quotaPolicyRecord("t1");
    when(redis.getJson(anyString(), eq(TenantQuotaPolicyEntity.class))).thenReturn(cached);

    assertThat(service.findEnabledQuotaPolicy("t1")).isSameAs(cached);
    assertThat(service.findEnabledQuotaPolicy("t1")).isSameAs(cached);

    verify(redis, times(1)).getJson(anyString(), eq(TenantQuotaPolicyEntity.class));
  }

  @Test
  void evictQuotaPoliciesClearsLocalPositiveCache() {
    TenantQuotaPolicyEntity cached = quotaPolicyRecord("t1");
    when(redis.getJson(anyString(), eq(TenantQuotaPolicyEntity.class)))
        .thenReturn(cached)
        .thenReturn(null);

    assertThat(service.findEnabledQuotaPolicy("t1")).isSameAs(cached);
    service.evictQuotaPolicies("t1");
    assertThat(service.findEnabledQuotaPolicy("t1")).isNull();

    verify(redis, times(2)).getJson(anyString(), eq(TenantQuotaPolicyEntity.class));
    verify(redis).delete("config:t1:tenant-quota-policy:enabled-first");
  }

  private static JobDefinitionEntity jobDefinitionRecord(String tenantId, String jobCode) {
    return new JobDefinitionEntity(
        1L, tenantId, jobCode, "Job", "IMPORT", "BIZ", "MANUAL", null, "UTC", "default", "default",
        null, null, "MANUAL", false, null, null, null, null, null, null, 5, null, 1, true, null,
        null, null);
  }

  private static TenantQuotaPolicyEntity quotaPolicyRecord(String tenantId) {
    return new TenantQuotaPolicyEntity(
        1L, tenantId, "default", 10, 20, 100, 1, null, 0, 0, "NONE", 0, true, null);
  }
}
