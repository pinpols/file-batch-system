package io.github.pinpols.batch.orchestrator.application.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.controller.response.SchedulerSnapshotResponse;
import io.github.pinpols.batch.orchestrator.domain.entity.QueuePartitionBacklogStats;
import io.github.pinpols.batch.orchestrator.domain.entity.ResourceQueueEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.orchestrator.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.orchestrator.mapper.TenantSchedulerSnapshotMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
  @DisplayName("buildLive: queue snapshot 带出分区积压、等待年龄和饱和度")
  void buildLiveIncludesQueueBacklog() {
    when(jobInstanceMapper.countActiveByTenant("ta")).thenReturn(2L);
    when(jobPartitionMapper.countActiveByTenant(
            anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(3L);
    when(quotaMapper.selectByTenantAndEnabled("ta", true)).thenReturn(List.of());
    when(queueMapper.selectByTenantAndEnabled("ta", true))
        .thenReturn(List.of(queue("import_queue", "IMPORT", 10, 5, "IMPORT")));
    when(workerRegistryMapper.selectByTenantAndStatus("ta", "ONLINE"))
        .thenReturn(List.of(worker("IMPORT")));
    when(jobInstanceMapper.countActiveByTenantAndQueueCodes(
            argThat("ta"::equals), argThat(c -> c.contains("import_queue"))))
        .thenReturn(List.of(Map.of("queueCode", "import_queue", "cnt", 2L)));
    when(jobPartitionMapper.summarizeQueueBacklogByTenantAndQueueCodes(
            argThat(p -> p.tenantId().equals("ta") && p.queueCodes().contains("import_queue"))))
        .thenReturn(List.of(new QueuePartitionBacklogStats("import_queue", 1, 3, 2, 1, 0, 120)));
    when(quotaRuntimeStateService.describe(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new QuotaRuntimeStateService.QuotaRuntimeSnapshot(null, 0, 0, 0, null, null, null));

    SchedulerSnapshotResponse resp = service.buildLive("ta");

    assertThat(resp.queues()).hasSize(1);
    SchedulerSnapshotResponse.QueueSnapshot q = resp.queues().getFirst();
    assertThat(q.queueCode()).isEqualTo("import_queue");
    assertThat(q.activeJobs()).isEqualTo(2);
    assertThat(q.createdPartitions()).isEqualTo(1);
    assertThat(q.waitingPartitions()).isEqualTo(3);
    assertThat(q.readyPartitions()).isEqualTo(2);
    assertThat(q.runningPartitions()).isEqualTo(1);
    assertThat(q.queuedPartitions()).isEqualTo(6);
    assertThat(q.activePartitions()).isEqualTo(3);
    assertThat(q.oldestWaitingSeconds()).isEqualTo(120);
    assertThat(q.tenantWaitingSharePermille()).isEqualTo(1000);
    assertThat(q.partitionSaturationPermille()).isEqualTo(600);
    assertThat(q.bottleneckReason()).isEqualTo("WAITING_DISPATCH_BACKLOG");
  }

  @Test
  @DisplayName("buildLive: WAITING 队列无在线 worker group → bottleneckReason=NO_ONLINE_WORKER")
  void buildLiveMarksQueueWithoutOnlineWorker() {
    when(jobInstanceMapper.countActiveByTenant("ta")).thenReturn(0L);
    when(jobPartitionMapper.countActiveByTenant(
            anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(0L);
    when(quotaMapper.selectByTenantAndEnabled("ta", true)).thenReturn(List.of());
    when(queueMapper.selectByTenantAndEnabled("ta", true))
        .thenReturn(List.of(queue("dispatch_queue", "DISPATCH", 10, 5, "DISPATCH")));
    when(workerRegistryMapper.selectByTenantAndStatus("ta", "ONLINE")).thenReturn(List.of());
    when(jobInstanceMapper.countActiveByTenantAndQueueCodes(
            argThat("ta"::equals), argThat(c -> c.contains("dispatch_queue"))))
        .thenReturn(List.of());
    when(jobPartitionMapper.summarizeQueueBacklogByTenantAndQueueCodes(
            argThat(p -> p.tenantId().equals("ta") && p.queueCodes().contains("dispatch_queue"))))
        .thenReturn(List.of(new QueuePartitionBacklogStats("dispatch_queue", 0, 1, 0, 0, 0, 30)));
    when(quotaRuntimeStateService.describe(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new QuotaRuntimeStateService.QuotaRuntimeSnapshot(null, 0, 0, 0, null, null, null));

    SchedulerSnapshotResponse resp = service.buildLive("ta");

    assertThat(resp.queues()).hasSize(1);
    assertThat(resp.queues().getFirst().bottleneckReason()).isEqualTo("NO_ONLINE_WORKER");
  }

  @Test
  @DisplayName("history: limit > 100 → clamp 到 100")
  void historyClampsUpperBound() {
    when(snapshotMapper.listRecent(anyString(), anyInt())).thenReturn(List.of());

    service.history("ta", 9999);

    ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
    verify(snapshotMapper).listRecent(anyString(), cap.capture());
    assertThat(cap.getValue()).isEqualTo(100);
  }

  @Test
  @DisplayName("history: limit < 1 → clamp 到 1")
  void historyClampsLowerBound() {
    when(snapshotMapper.listRecent(anyString(), anyInt())).thenReturn(List.of());

    service.history("ta", 0);

    ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
    verify(snapshotMapper).listRecent(anyString(), cap.capture());
    assertThat(cap.getValue()).isEqualTo(1);
  }

  @Test
  @DisplayName("history: 负数 limit → clamp 到 1")
  void historyNegativeClampsToOne() {
    when(snapshotMapper.listRecent(anyString(), anyInt())).thenReturn(List.of());

    service.history("ta", -50);

    ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
    verify(snapshotMapper).listRecent(anyString(), cap.capture());
    assertThat(cap.getValue()).isEqualTo(1);
  }

  @Test
  @DisplayName("history: 合法范围内 limit 透传")
  void historyPassesThroughValidLimit() {
    when(snapshotMapper.listRecent(anyString(), anyInt())).thenReturn(List.of());

    service.history("ta", 25);

    verify(snapshotMapper).listRecent("ta", 25);
  }

  @Test
  @DisplayName("history: 返回 mapper 的列表(无需关心 entity 字段)")
  @SuppressWarnings("unchecked")
  void historyReturnsMapperResult() {
    @SuppressWarnings("rawtypes")
    List mockList = List.of();
    when(snapshotMapper.listRecent("ta", 10)).thenReturn(mockList);

    assertThat(service.history("ta", 10)).isSameAs(mockList);
  }

  private static ResourceQueueEntity queue(
      String queueCode,
      String queueType,
      Integer maxRunningJobs,
      Integer maxRunningPartitions,
      String workerGroup) {
    return new ResourceQueueEntity(
        1L,
        "ta",
        queueCode,
        queueCode,
        queueType,
        maxRunningJobs,
        maxRunningPartitions,
        100,
        workerGroup,
        null,
        "FIFO",
        1,
        null,
        0,
        "NONE",
        null,
        true);
  }

  private static WorkerRegistryEntity worker(String workerGroup) {
    return new WorkerRegistryEntity(
        1L,
        "ta",
        "worker-" + workerGroup,
        workerGroup,
        null,
        null,
        "ONLINE",
        Instant.now(),
        0,
        10,
        null,
        null);
  }
}
