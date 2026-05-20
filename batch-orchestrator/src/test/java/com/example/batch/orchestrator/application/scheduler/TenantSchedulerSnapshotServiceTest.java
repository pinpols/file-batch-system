package com.example.batch.orchestrator.application.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import com.example.batch.orchestrator.controller.response.SchedulerSnapshotResponse;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.ResourceQueueMapper;
import com.example.batch.orchestrator.mapper.TenantQuotaPolicyMapper;
import com.example.batch.orchestrator.mapper.TenantSchedulerSnapshotMapper;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * 守护租户调度快照的输入约束:
 *
 * <ul>
 *   <li>buildLive: 空/null tenantId → 返空快照,不读 DB(防止跨租户泄漏)
 *   <li>history: limit 被 clamp 到 [1, 100],防止恶意翻页拖库
 * </ul>
 */
class TenantSchedulerSnapshotServiceTest {

  @Mock private TenantQuotaPolicyMapper quotaMapper;
  @Mock private TenantSchedulerSnapshotMapper snapshotMapper;
  @Mock private ResourceQueueMapper queueMapper;
  @Mock private JobInstanceMapper jobInstanceMapper;
  @Mock private JobPartitionMapper jobPartitionMapper;
  @Mock private WorkerRegistryMapper workerRegistryMapper;
  @Mock private QuotaRuntimeStateService quotaRuntimeStateService;
  @Mock private ResourceSchedulerProperties resourceSchedulerProperties;

  private TenantSchedulerSnapshotService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service =
        new TenantSchedulerSnapshotService(
            quotaMapper,
            snapshotMapper,
            queueMapper,
            jobInstanceMapper,
            jobPartitionMapper,
            workerRegistryMapper,
            quotaRuntimeStateService,
            resourceSchedulerProperties);
  }

  @Test
  @DisplayName("buildLive: null tenantId → 返空快照,不读任何 mapper")
  void buildLive_null_tenant_returns_empty() {
    SchedulerSnapshotResponse resp = service.buildLive(null);
    assertThat(resp).isNotNull();
    assertThat(resp.policies()).isEmpty();
    assertThat(resp.queues()).isEmpty();
    assertThat(resp.workers()).isEmpty();
    verify(jobInstanceMapper, never()).countActiveByTenant(anyString());
    verify(quotaMapper, never()).selectByTenantAndEnabled(anyString(), anyBoolean());
  }

  @Test
  @DisplayName("buildLive: 空白 tenantId → 同样不读 DB")
  void buildLive_blank_tenant_returns_empty() {
    SchedulerSnapshotResponse resp = service.buildLive("   ");
    assertThat(resp.policies()).isEmpty();
    verify(jobInstanceMapper, never()).countActiveByTenant(anyString());
  }

  @Test
  @DisplayName("history: limit > 100 → clamp 到 100")
  void history_clamps_upper_bound() {
    when(snapshotMapper.listRecent(anyString(), anyInt())).thenReturn(List.of());

    service.history("ta", 9999);

    ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
    verify(snapshotMapper).listRecent(anyString(), cap.capture());
    assertThat(cap.getValue()).isEqualTo(100);
  }

  @Test
  @DisplayName("history: limit < 1 → clamp 到 1")
  void history_clamps_lower_bound() {
    when(snapshotMapper.listRecent(anyString(), anyInt())).thenReturn(List.of());

    service.history("ta", 0);

    ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
    verify(snapshotMapper).listRecent(anyString(), cap.capture());
    assertThat(cap.getValue()).isEqualTo(1);
  }

  @Test
  @DisplayName("history: 负数 limit → clamp 到 1")
  void history_negative_clamps_to_one() {
    when(snapshotMapper.listRecent(anyString(), anyInt())).thenReturn(List.of());

    service.history("ta", -50);

    ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
    verify(snapshotMapper).listRecent(anyString(), cap.capture());
    assertThat(cap.getValue()).isEqualTo(1);
  }

  @Test
  @DisplayName("history: 合法范围内 limit 透传")
  void history_passes_through_valid_limit() {
    when(snapshotMapper.listRecent(anyString(), anyInt())).thenReturn(List.of());

    service.history("ta", 25);

    verify(snapshotMapper).listRecent("ta", 25);
  }

  @Test
  @DisplayName("history: 返回 mapper 的列表(无需关心 entity 字段)")
  @SuppressWarnings("unchecked")
  void history_returns_mapper_result() {
    @SuppressWarnings("rawtypes")
    List mockList = List.of();
    when(snapshotMapper.listRecent("ta", 10)).thenReturn(mockList);

    assertThat(service.history("ta", 10)).isSameAs(mockList);
  }
}
