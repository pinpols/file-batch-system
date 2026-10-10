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
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchWindowMapper;
import io.github.pinpols.batch.orchestrator.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("编排配置缓存服务:验证空参数短路,远端缓存命中与回源,以及本地缓存失效后的重读")
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
  @DisplayName("租户标识为空或空白时直接返回空结果,不访问远端缓存")
  void shouldReturnNull_whenTenantIdNullOrBlank() {
    assertThat(service.findEnabledJobDefinition(null, "JOB1")).isNull();
    assertThat(service.findEnabledJobDefinition("", "JOB1")).isNull();
    assertThat(service.findEnabledJobDefinition("  ", "JOB1")).isNull();
    verify(redis, never()).getJson(anyString(), any());
  }

  @Test
  @DisplayName("作业编码为空或空白时直接返回空结果,不访问远端缓存")
  void shouldReturnNull_whenJobCodeNullOrBlank() {
    assertThat(service.findEnabledJobDefinition("t1", null)).isNull();
    assertThat(service.findEnabledJobDefinition("t1", "")).isNull();
    verify(redis, never()).getJson(anyString(), any());
  }

  @Test
  @DisplayName("远端缓存命中时直接返回缓存值,既不查询数据库也不回写缓存")
  void shouldReturnCachedValue_whenRemoteCacheHit() {
    JobDefinitionEntity cached = jobDefinitionRecord("t1", "JOB1");
    when(redis.getJson(anyString(), eq(JobDefinitionEntity.class))).thenReturn(cached);

    JobDefinitionEntity result = service.findEnabledJobDefinition("t1", "JOB1");

    assertThat(result).isSameAs(cached);
    verify(jobDefinitionMapper, never()).selectFirstByTenantAndCodeAndEnabled(any(), any(), any());
    verify(redis, never()).setJson(anyString(), any(), any(Duration.class));
  }

  @Test
  @DisplayName("同一作业定义连续读取两次时只有第一次访问远端缓存")
  void shouldHitRemoteCacheOnce_whenSameJobDefinitionReadTwice() {
    JobDefinitionEntity cached = jobDefinitionRecord("t1", "JOB1");
    when(redis.getJson(anyString(), eq(JobDefinitionEntity.class))).thenReturn(cached);

    assertThat(service.findEnabledJobDefinition("t1", "JOB1")).isSameAs(cached);
    assertThat(service.findEnabledJobDefinition("t1", "JOB1")).isSameAs(cached);

    verify(redis, times(1)).getJson(anyString(), eq(JobDefinitionEntity.class));
  }

  @Test
  @DisplayName("作业定义缓存被清除后重新读取远端缓存,远端无数据时返回空结果")
  void shouldReadThroughAfterEvict_whenJobDefinitionCacheCleared() {
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
  @DisplayName("远端缓存未命中时查询数据库并把结果写回远端缓存")
  void shouldQueryRepositoryAndWriteCache_whenRemoteCacheMiss() {
    JobDefinitionEntity fromDb = jobDefinitionRecord("t1", "JOB2");
    when(redis.getJson(anyString(), eq(JobDefinitionEntity.class))).thenReturn(null);
    when(jobDefinitionMapper.selectFirstByTenantAndCodeAndEnabled("t1", "JOB2", true))
        .thenReturn(fromDb);

    JobDefinitionEntity result = service.findEnabledJobDefinition("t1", "JOB2");

    assertThat(result).isSameAs(fromDb);
    verify(redis).setJson(anyString(), eq(fromDb), any(Duration.class));
  }

  @Test
  @DisplayName("清除作业定义缓存时删除对应的远端缓存键")
  void shouldDeleteRemoteKey_whenJobDefinitionEvicted() {
    service.evictJobDefinition("t1", "JOB3");

    verify(redis).delete("config:t1:job-definition:JOB3");
  }

  @Test
  @DisplayName("清除工作流定义缓存时保持工作流缓存类型键一致")
  void shouldDeleteRemoteKey_whenWorkflowDefinitionEvicted() {
    service.evictWorkflowDefinition("t1", "WF1");

    verify(redis).delete("config:t1:workflow-definition:WF1");
  }

  @Test
  @DisplayName("读取工作流定义时使用一致的缓存类型键")
  void shouldUseWorkflowDefinitionCacheType_whenReading() {
    WorkflowDefinitionEntity cached = workflowDefinitionRecord("t1", "WF1");
    when(redis.getJson("config:t1:workflow-definition:WF1", WorkflowDefinitionEntity.class))
        .thenReturn(cached);

    assertThat(service.findEnabledWorkflowDefinition("t1", "WF1")).isSameAs(cached);

    verify(workflowDefinitionMapper, never())
        .selectFirstByTenantAndCodeAndEnabled(any(), any(), any());
  }

  @Test
  @DisplayName("按工作流定义类型清除本地缓存后重新读取")
  void shouldReloadWorkflowDefinition_whenTypeCacheEvicted() {
    WorkflowDefinitionEntity fromDb = workflowDefinitionRecord("t1", "WF1");
    when(redis.getJson(anyString(), eq(WorkflowDefinitionEntity.class))).thenReturn(null);
    when(workflowDefinitionMapper.selectFirstByTenantAndCodeAndEnabled("t1", "WF1", true))
        .thenReturn(fromDb);

    assertThat(service.findEnabledWorkflowDefinition("t1", "WF1")).isSameAs(fromDb);
    service.evictLocal("t1", "workflow-definition", "*");
    assertThat(service.findEnabledWorkflowDefinition("t1", "WF1")).isSameAs(fromDb);

    verify(workflowDefinitionMapper, times(2))
        .selectFirstByTenantAndCodeAndEnabled("t1", "WF1", true);
  }

  @Test
  @DisplayName("同一租户配额策略连续读取两次时只有第一次访问远端缓存")
  void shouldHitRemoteCacheOnce_whenSameQuotaPolicyReadTwice() {
    TenantQuotaPolicyEntity cached = quotaPolicyRecord("t1");
    when(redis.getJson(anyString(), eq(TenantQuotaPolicyEntity.class))).thenReturn(cached);

    assertThat(service.findEnabledQuotaPolicy("t1")).isSameAs(cached);
    assertThat(service.findEnabledQuotaPolicy("t1")).isSameAs(cached);

    verify(redis, times(1)).getJson(anyString(), eq(TenantQuotaPolicyEntity.class));
  }

  @Test
  @DisplayName("租户配额策略缓存被清除后重新读取远端缓存,并删除远端缓存键")
  void shouldReadThroughAndDeleteKey_whenQuotaPolicyCacheCleared() {
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

  private static WorkflowDefinitionEntity workflowDefinitionRecord(
      String tenantId, String workflowCode) {
    return new WorkflowDefinitionEntity(
        1L, tenantId, workflowCode, "Workflow", "STANDARD", 1, true);
  }
}
