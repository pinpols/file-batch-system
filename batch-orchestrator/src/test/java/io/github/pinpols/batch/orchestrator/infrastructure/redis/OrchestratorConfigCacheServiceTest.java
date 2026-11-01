package io.github.pinpols.batch.orchestrator.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchWindowEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.domain.view.JobDefinitionView;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowDefinitionView;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    lenient()
        .when(redis.configInvalidationVersions(anyString(), anyString(), anyString()))
        .thenReturn("0:0");
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
    JobDefinitionView cached = jobDefinitionRecord("t1", "JOB1");
    when(redis.getJson(anyString(), eq(JobDefinitionView.class))).thenReturn(cached);

    JobDefinitionView result = service.findEnabledJobDefinition("t1", "JOB1");

    assertThat(result).isSameAs(cached);
    verify(jobDefinitionMapper, never()).selectFirstByTenantAndCodeAndEnabled(any(), any(), any());
    verify(redis, never()).setJson(anyString(), any(), any(Duration.class));
  }

  @Test
  @DisplayName("同一作业定义连续读取两次时只有第一次访问远端缓存")
  void shouldHitRemoteCacheOnce_whenSameJobDefinitionReadTwice() {
    JobDefinitionView cached = jobDefinitionRecord("t1", "JOB1");
    when(redis.getJson(anyString(), eq(JobDefinitionView.class))).thenReturn(cached);

    assertThat(service.findEnabledJobDefinition("t1", "JOB1")).isSameAs(cached);
    assertThat(service.findEnabledJobDefinition("t1", "JOB1")).isSameAs(cached);

    verify(redis, times(1)).getJson(anyString(), eq(JobDefinitionView.class));
  }

  @Test
  @DisplayName("作业定义缓存被清除后重新读取远端缓存,远端无数据时返回空结果")
  void shouldReadThroughAfterEvict_whenJobDefinitionCacheCleared() {
    JobDefinitionView cached = jobDefinitionRecord("t1", "JOB3");
    when(redis.getJson(anyString(), eq(JobDefinitionView.class)))
        .thenReturn(cached)
        .thenReturn(null);

    assertThat(service.findEnabledJobDefinition("t1", "JOB3")).isSameAs(cached);
    service.evictJobDefinition("t1", "JOB3");
    assertThat(service.findEnabledJobDefinition("t1", "JOB3")).isNull();

    verify(redis, times(2)).getJson(anyString(), eq(JobDefinitionView.class));
  }

  @Test
  @DisplayName("远端缓存未命中时查询数据库并把结果写回远端缓存")
  void shouldQueryRepositoryAndWriteCache_whenRemoteCacheMiss() {
    JobDefinitionView fromDb = jobDefinitionRecord("t1", "JOB2");
    when(redis.getJson(anyString(), eq(JobDefinitionView.class))).thenReturn(null);
    when(jobDefinitionMapper.selectFirstByTenantAndCodeAndEnabled("t1", "JOB2", true))
        .thenReturn(fromDb);

    JobDefinitionView result = service.findEnabledJobDefinition("t1", "JOB2");

    assertThat(result).isSameAs(fromDb);
    verify(redis).setJson(anyString(), eq(fromDb), any(Duration.class));
  }

  @Test
  @DisplayName("无法读取失效版本时回源数据库且不写入共享或本地缓存")
  void shouldBypassCaches_whenInvalidationRevisionIsUnavailable() {
    JobDefinitionView fromDb = jobDefinitionRecord("t1", "JOB2");
    when(redis.configInvalidationVersions(anyString(), anyString(), anyString()))
        .thenReturn(null);
    when(jobDefinitionMapper.selectFirstByTenantAndCodeAndEnabled("t1", "JOB2", true))
        .thenReturn(fromDb);

    assertThat(service.findEnabledJobDefinition("t1", "JOB2")).isSameAs(fromDb);
    assertThat(service.findEnabledJobDefinition("t1", "JOB2")).isSameAs(fromDb);

    verify(jobDefinitionMapper, times(2)).selectFirstByTenantAndCodeAndEnabled("t1", "JOB2", true);
    verify(redis, never()).getJson(anyString(), any());
    verify(redis, never()).setJson(anyString(), any(), any(Duration.class));
  }

  @Test
  @DisplayName("已有本地正缓存命中时仍按短 TTL 复用，不额外读取失效版本")
  void shouldRetainExistingLocalEntryUntilEvictedOrExpired() {
    JobDefinitionView cached = jobDefinitionRecord("t1", "JOB2");
    when(redis.getJson(anyString(), eq(JobDefinitionView.class))).thenReturn(cached);

    assertThat(service.findEnabledJobDefinition("t1", "JOB2")).isSameAs(cached);
    assertThat(service.findEnabledJobDefinition("t1", "JOB2")).isSameAs(cached);

    verify(redis, times(1)).configInvalidationVersions(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("未收到广播但共享版本已推进时，旧版本负缓存不阻止重新回源")
  void shouldIgnoreNegativeEntry_whenRevisionAdvancesWithoutEvent() {
    JobDefinitionView created = jobDefinitionRecord("t1", "JOB2");
    when(redis.configInvalidationVersions(anyString(), anyString(), anyString()))
        .thenReturn("0:1", "0:2");
    when(jobDefinitionMapper.selectFirstByTenantAndCodeAndEnabled("t1", "JOB2", true))
        .thenReturn(null, created);

    assertThat(service.findEnabledJobDefinition("t1", "JOB2")).isNull();
    assertThat(service.findEnabledJobDefinition("t1", "JOB2")).isSameAs(created);

    verify(jobDefinitionMapper, times(2)).selectFirstByTenantAndCodeAndEnabled("t1", "JOB2", true);
  }

  @Test
  @DisplayName("业务日历和批次窗口回源后使用对应租户、类型与组合版本的缓存键")
  void shouldUseScopedVersionedKeys_forCalendarAndWindow() {
    BusinessCalendarEntity calendar = BusinessCalendarEntity.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .enabled(true)
        .build();
    BatchWindowEntity window =
        new BatchWindowEntity(1L, "t1", "WIN", null, null, null, null, null, null, false, true);
    when(businessCalendarMapper.selectFirstByTenantAndCodeAndEnabled("t1", "CAL", true))
        .thenReturn(calendar);
    when(batchWindowMapper.selectFirstByTenantAndCodeAndEnabled("t1", "WIN", true))
        .thenReturn(window);

    assertThat(service.findEnabledBusinessCalendar("t1", "CAL")).isSameAs(calendar);
    assertThat(service.findEnabledBatchWindow("t1", "WIN")).isSameAs(window);

    verify(redis)
        .setJson(eq("config:t1:business-calendar:CAL:v2:0:0"), eq(calendar), any(Duration.class));
    verify(redis).setJson(eq("config:t1:batch-window:WIN:v2:0:0"), eq(window), any(Duration.class));
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        TransactionDefinition.ISOLATION_REPEATABLE_READ,
        TransactionDefinition.ISOLATION_SERIALIZABLE
      })
  @DisplayName("固定数据库快照事务中直接读取仓储，不读取或回填共享配置缓存")
  void shouldBypassCaches_whenTransactionUsesFixedSnapshot(int isolation) {
    JobDefinitionView snapshot = jobDefinitionRecord("t1", "JOB2");
    when(jobDefinitionMapper.selectFirstByTenantAndCodeAndEnabled("t1", "JOB2", true))
        .thenReturn(snapshot);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
    try {
      assertThat(service.findEnabledJobDefinition("t1", "JOB2")).isSameAs(snapshot);
    } finally {
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    verify(redis, never()).configInvalidationVersions(anyString(), anyString(), anyString());
    verify(redis, never()).getJson(anyString(), any());
    verify(redis, never()).setJson(anyString(), any(), any(Duration.class));
  }

  @Test
  @DisplayName("工作流、日历、窗口和配额在重复读事务中均直接读取数据库")
  void shouldReadOtherConfigTypesFromTransactionSnapshot() {
    WorkflowDefinitionView workflow = workflowDefinitionRecord("t1", "WF1");
    BusinessCalendarEntity calendar = BusinessCalendarEntity.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .enabled(true)
        .build();
    BatchWindowEntity window =
        new BatchWindowEntity(1L, "t1", "WIN", null, null, null, null, null, null, false, true);
    TenantQuotaPolicyEntity quota = quotaPolicyRecord("t1");
    when(workflowDefinitionMapper.selectFirstByTenantAndCodeAndEnabled("t1", "WF1", true))
        .thenReturn(workflow);
    when(workflowDefinitionMapper.selectFirstByTenantAndSingleJobDefinitionIdAndEnabled(
            "t1", 1L, true))
        .thenReturn(workflow);
    when(businessCalendarMapper.selectFirstByTenantAndCodeAndEnabled("t1", "CAL", true))
        .thenReturn(calendar);
    when(batchWindowMapper.selectFirstByTenantAndCodeAndEnabled("t1", "WIN", true))
        .thenReturn(window);
    when(tenantQuotaPolicyMapper.selectFirstEnabledByTenantId("t1", true)).thenReturn(quota);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_REPEATABLE_READ);
    try {
      assertThat(service.findEnabledWorkflowDefinition("t1", "WF1")).isSameAs(workflow);
      assertThat(service.findEnabledSingleJobWorkflowDefinition("t1", 1L)).isSameAs(workflow);
      assertThat(service.findEnabledBusinessCalendar("t1", "CAL")).isSameAs(calendar);
      assertThat(service.findEnabledBatchWindow("t1", "WIN")).isSameAs(window);
      assertThat(service.findEnabledQuotaPolicy("t1")).isSameAs(quota);
    } finally {
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    verify(redis, never()).configInvalidationVersions(anyString(), anyString(), anyString());
    verify(redis, never()).getJson(anyString(), any());
    verify(redis, never()).setJson(anyString(), any(), any(Duration.class));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("活跃事务中的仓储旧值或旧缺失不回填到事务外的正负缓存")
  void shouldNotCacheRepositoryResult_whenTransactionIsActive(boolean present) {
    JobDefinitionView selected = present ? jobDefinitionRecord("t1", "JOB2") : null;
    when(jobDefinitionMapper.selectFirstByTenantAndCodeAndEnabled("t1", "JOB2", true))
        .thenReturn(selected);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
    try {
      assertThat(service.findEnabledJobDefinition("t1", "JOB2")).isSameAs(selected);
    } finally {
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    assertThat(service.findEnabledJobDefinition("t1", "JOB2")).isSameAs(selected);
    verify(jobDefinitionMapper, times(2)).selectFirstByTenantAndCodeAndEnabled("t1", "JOB2", true);
    verify(redis, times(present ? 1 : 0)).setJson(anyString(), any(), any(Duration.class));
  }

  @Test
  @DisplayName("普通事务仍可读取已经命中的共享配置缓存")
  void shouldReadSharedCache_whenReadCommittedTransactionIsActive() {
    JobDefinitionView cached = jobDefinitionRecord("t1", "JOB2");
    when(redis.getJson(anyString(), eq(JobDefinitionView.class))).thenReturn(cached);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
    try {
      assertThat(service.findEnabledJobDefinition("t1", "JOB2")).isSameAs(cached);
    } finally {
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    verify(jobDefinitionMapper, never()).selectFirstByTenantAndCodeAndEnabled(any(), any(), any());
    verify(redis, never()).setJson(anyString(), any(), any(Duration.class));
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
    WorkflowDefinitionView cached = workflowDefinitionRecord("t1", "WF1");
    when(redis.getJson("config:t1:workflow-definition:WF1:v2:0:0", WorkflowDefinitionView.class))
        .thenReturn(cached);

    assertThat(service.findEnabledWorkflowDefinition("t1", "WF1")).isSameAs(cached);

    verify(workflowDefinitionMapper, never())
        .selectFirstByTenantAndCodeAndEnabled(any(), any(), any());
  }

  @Test
  @DisplayName("单作业 Workflow 按显式 Job 定义 ID 查找并使用短期本地缓存")
  void shouldResolveSingleJobWorkflowByDefinitionId() {
    WorkflowDefinitionView workflow = new WorkflowDefinitionView(
        91L, "t1", "JOB-WF-41", "Job Workflow", "DAG", 1, true, 41L, null);
    when(workflowDefinitionMapper.selectFirstByTenantAndSingleJobDefinitionIdAndEnabled(
            "t1", 41L, true))
        .thenReturn(workflow);

    assertThat(service.findEnabledSingleJobWorkflowDefinition("t1", 41L)).isSameAs(workflow);
    assertThat(service.findEnabledSingleJobWorkflowDefinition("t1", 41L)).isSameAs(workflow);

    verify(workflowDefinitionMapper)
        .selectFirstByTenantAndSingleJobDefinitionIdAndEnabled("t1", 41L, true);
    verify(redis, never()).getJson(anyString(), eq(WorkflowDefinitionView.class));
  }

  @Test
  @DisplayName("按工作流定义类型清除本地缓存后重新读取")
  void shouldReloadWorkflowDefinition_whenTypeCacheEvicted() {
    WorkflowDefinitionView fromDb = workflowDefinitionRecord("t1", "WF1");
    when(redis.getJson(anyString(), eq(WorkflowDefinitionView.class))).thenReturn(null);
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

  private static JobDefinitionView jobDefinitionRecord(String tenantId, String jobCode) {
    return new JobDefinitionView(
        1L, tenantId, jobCode, "Job", "IMPORT", "BIZ", "MANUAL", null, "UTC", "default", "default",
        null, null, "MANUAL", false, null, null, null, null, null, null, 5, null, 1, true, null,
        null, null);
  }

  private static TenantQuotaPolicyEntity quotaPolicyRecord(String tenantId) {
    return new TenantQuotaPolicyEntity(
        1L, tenantId, "default", 10, 20, 100, 1, null, 0, 0, "NONE", 0, true, null);
  }

  private static WorkflowDefinitionView workflowDefinitionRecord(
      String tenantId, String workflowCode) {
    return new WorkflowDefinitionView(
        1L, tenantId, workflowCode, "Workflow", "STANDARD", 1, true, null, null);
  }
}
