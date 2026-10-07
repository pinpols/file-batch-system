package io.github.pinpols.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.service.governance.DefaultWorkerDrainGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.config.WorkerDrainProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("工作节点排空治理服务: 排空开启, 强制下线, 超时接管与预热口径")
class DefaultWorkerDrainGovernanceServiceTest {

  private WorkerRegistryMapper workerRegistryMapper;
  private JobTaskMapper jobTaskMapper;
  private RetryGovernanceService retryGovernanceService;
  private WorkerDrainProperties drainProperties;
  private DefaultWorkerDrainGovernanceService service;

  @BeforeEach
  void setUp() {
    workerRegistryMapper = mock(WorkerRegistryMapper.class);
    jobTaskMapper = mock(JobTaskMapper.class);
    retryGovernanceService = mock(RetryGovernanceService.class);
    drainProperties = new WorkerDrainProperties();
    drainProperties.setDefaultTimeoutSeconds(300);
    service = new DefaultWorkerDrainGovernanceService(
        workerRegistryMapper, jobTaskMapper, retryGovernanceService, drainProperties);
  }

  // ── startDrain ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("开启排空时租户标识为空则抛出业务异常")
  void shouldThrowWhenTenantIdIsBlankOnStartDrain() {
    assertThatThrownBy(() -> service.startDrain("", "w1", null)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("开启排空时节点编码为空则抛出业务异常")
  void shouldThrowWhenWorkerCodeIsBlankOnStartDrain() {
    assertThatThrownBy(() -> service.startDrain("t1", "", null)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("开启排空时节点未注册则抛出业务异常")
  void shouldThrowWhenWorkerNotRegisteredOnStartDrain() {
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1")).thenReturn(null);

    assertThatThrownBy(() -> service.startDrain("t1", "w1", null)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("节点已下线时再次开启排空抛出业务异常")
  void shouldThrowWhenWorkerAlreadyDecommissionedOnStartDrain() {
    WorkerRegistryEntity registry = onlineWorker("t1", "w1")
        .withStatus(WorkerRegistryStatus.DECOMMISSIONED.code(), BatchDateTimeSupport.utcNow());
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1")).thenReturn(registry);

    assertThatThrownBy(() -> service.startDrain("t1", "w1", null)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("未指定超时时使用默认值, 状态置为排空中并记录开始与截止时间")
  void shouldSetDrainingStatusWithDefaultTimeout() {
    WorkerRegistryEntity registry = onlineWorker("t1", "w1");
    var now = BatchDateTimeSupport.utcNow();
    WorkerRegistryEntity draining =
        registry.withDrain(WorkerRegistryStatus.DRAINING.code(), now, now.plusSeconds(300), now);
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1"))
        .thenReturn(registry, draining);
    when(workerRegistryMapper.startDrainIfCurrent(
            "t1", "w1", WorkerRegistryStatus.ONLINE.code(), 300))
        .thenReturn(1);

    WorkerRegistryEntity result = service.startDrain("t1", "w1", null);

    assertThat(result.status()).isEqualTo(WorkerRegistryStatus.DRAINING.code());
    assertThat(result.drainStartedAt()).isNotNull();
    assertThat(result.drainDeadlineAt()).isNotNull();
    assertThat(result.drainDeadlineAt()).isAfter(result.drainStartedAt());
  }

  @Test
  @DisplayName("指定超时时按给定秒数计算排空截止时间")
  void shouldUseCustomTimeoutWhenProvided() {
    WorkerRegistryEntity registry = onlineWorker("t1", "w1");
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1"))
        .thenReturn(registry, registry);
    when(workerRegistryMapper.startDrainIfCurrent(
            "t1", "w1", WorkerRegistryStatus.ONLINE.code(), 120))
        .thenReturn(1);

    service.startDrain("t1", "w1", 120);

    verify(workerRegistryMapper)
        .startDrainIfCurrent("t1", "w1", WorkerRegistryStatus.ONLINE.code(), 120);
  }

  @Test
  @DisplayName("开启排空时状态已被并发修改则抛出业务异常")
  void shouldThrowOnConcurrentChange_whenStartingDrain() {
    WorkerRegistryEntity registry = onlineWorker("t1", "w1");
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1")).thenReturn(registry);

    assertThatThrownBy(() -> service.startDrain("t1", "w1", 120)).isInstanceOf(BizException.class);
  }

  // ── forceOffline ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("强制下线时租户标识为空则抛出业务异常")
  void shouldThrowWhenTenantIdIsBlankOnForceOffline() {
    assertThatThrownBy(() -> service.forceOffline("", "w1")).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("强制下线时节点未注册则抛出业务异常")
  void shouldThrowWhenWorkerNotRegisteredOnForceOffline() {
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1")).thenReturn(null);

    assertThatThrownBy(() -> service.forceOffline("t1", "w1")).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("强制下线时标记节点已下线并回收其活跃任务")
  void shouldMarkDecommissionedAndTakeoverTasksOnForceOffline() {
    WorkerRegistryEntity registry = onlineWorker("t1", "w1");
    WorkerRegistryEntity decommissioned =
        registry.withDecommissioned(BatchDateTimeSupport.utcNow());
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1"))
        .thenReturn(registry)
        .thenReturn(registry)
        .thenReturn(decommissioned);
    when(workerRegistryMapper.markDecommissioned("t1", "w1")).thenReturn(1);

    JobTaskEntity task = new JobTaskEntity();
    task.setId(100L);
    task.setTenantId("t1");
    when(jobTaskMapper.selectActiveByAssignedWorker("t1", "w1")).thenReturn(List.of(task));

    WorkerRegistryEntity result = service.forceOffline("t1", "w1");

    assertThat(result.status()).isEqualTo(WorkerRegistryStatus.DECOMMISSIONED.code());
    verify(retryGovernanceService).reclaimTask(eq("t1"), eq(100L), anyString());
  }

  @Test
  @DisplayName("没有活跃任务时强制下线照常完成, 不触发任务回收")
  void shouldCompleteForceOfflineEvenWhenNoActiveTasks() {
    WorkerRegistryEntity registry = onlineWorker("t1", "w1");
    WorkerRegistryEntity decommissioned =
        registry.withDecommissioned(BatchDateTimeSupport.utcNow());
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1"))
        .thenReturn(registry)
        .thenReturn(registry)
        .thenReturn(decommissioned);
    when(workerRegistryMapper.markDecommissioned("t1", "w1")).thenReturn(1);
    when(jobTaskMapper.selectActiveByAssignedWorker("t1", "w1")).thenReturn(List.of());

    WorkerRegistryEntity result = service.forceOffline("t1", "w1");

    assertThat(result.status()).isEqualTo(WorkerRegistryStatus.DECOMMISSIONED.code());
    verify(retryGovernanceService, never()).reclaimTask(anyString(), anyLong(), anyString());
  }

  // ── listClaimedTasks ─────────────────────────────────────────────────────

  @Test
  @DisplayName("查询认领任务时节点编码为空则抛出业务异常")
  void shouldThrowWhenWorkerCodeIsBlankOnListClaimedTasks() {
    assertThatThrownBy(() -> service.listClaimedTasks("t1", "")).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("查询认领任务时返回该节点的活跃任务")
  void shouldReturnActiveTasksForWorker() {
    JobTaskEntity task = new JobTaskEntity();
    task.setId(200L);
    when(jobTaskMapper.selectActiveByAssignedWorker("t1", "w1")).thenReturn(List.of(task));

    List<JobTaskEntity> tasks = service.listClaimedTasks("t1", "w1");

    assertThat(tasks).hasSize(1);
    assertThat(tasks.get(0).getId()).isEqualTo(200L);
  }

  // ── takeoverAfterDrainTimeout ─────────────────────────────────────────────

  @Test
  @DisplayName("超时接管时租户标识为空则不查询节点注册信息")
  void shouldDoNothingWhenTenantIdIsBlankOnTakeover() {
    service.takeoverAfterDrainTimeout("", "w1");
    verify(workerRegistryMapper, never()).selectByTenantAndWorkerCode(anyString(), anyString());
  }

  @Test
  @DisplayName("超时接管时节点编码为空则不查询节点注册信息")
  void shouldDoNothingWhenWorkerCodeIsBlankOnTakeover() {
    service.takeoverAfterDrainTimeout("t1", "");
    verify(workerRegistryMapper, never()).selectByTenantAndWorkerCode(anyString(), anyString());
  }

  @Test
  @DisplayName("超时接管时节点注册信息不存在则不查询活跃任务")
  void shouldDoNothingWhenRegistryNotFoundOnTakeover() {
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1")).thenReturn(null);

    service.takeoverAfterDrainTimeout("t1", "w1");

    verify(jobTaskMapper, never()).selectActiveByAssignedWorker(anyString(), anyString());
  }

  @Test
  @DisplayName("节点不处于排空中时超时接管不查询活跃任务")
  void shouldDoNothingWhenWorkerNotDrainingOnTakeover() {
    WorkerRegistryEntity registry = onlineWorker("t1", "w1");
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1")).thenReturn(registry);

    service.takeoverAfterDrainTimeout("t1", "w1");

    verify(jobTaskMapper, never()).selectActiveByAssignedWorker(anyString(), anyString());
  }

  @Test
  @DisplayName("节点处于排空中时执行接管并将其标记为已下线")
  void shouldTakeoverAndDecommissionWhenDrainingWorkerFound() {
    WorkerRegistryEntity registry = onlineWorker("t1", "w1")
        .withDrain(
            WorkerRegistryStatus.DRAINING.code(),
            BatchDateTimeSupport.utcNow().minusSeconds(600),
            BatchDateTimeSupport.utcNow().minusSeconds(100),
            BatchDateTimeSupport.utcNow().minusSeconds(600));

    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1"))
        .thenReturn(registry)
        .thenReturn(registry);
    when(workerRegistryMapper.markDecommissioned("t1", "w1")).thenReturn(1);
    when(jobTaskMapper.selectActiveByAssignedWorker("t1", "w1")).thenReturn(List.of());

    service.takeoverAfterDrainTimeout("t1", "w1");

    verify(workerRegistryMapper).markDecommissioned("t1", "w1");
  }

  @Test
  @DisplayName("单个任务回收失败时继续处理其余任务, 整体不抛异常")
  void shouldContinueTakeoverWhenOneTaskRetryFails() {
    WorkerRegistryEntity registry = onlineWorker("t1", "w1")
        .withStatus(WorkerRegistryStatus.DRAINING.code(), BatchDateTimeSupport.utcNow());
    WorkerRegistryEntity decommissioned =
        registry.withDecommissioned(BatchDateTimeSupport.utcNow());
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1"))
        .thenReturn(registry)
        .thenReturn(registry)
        .thenReturn(decommissioned);
    when(workerRegistryMapper.markDecommissioned("t1", "w1")).thenReturn(1);

    JobTaskEntity task1 = new JobTaskEntity();
    task1.setId(301L);
    task1.setTenantId("t1");
    JobTaskEntity task2 = new JobTaskEntity();
    task2.setId(302L);
    task2.setTenantId("t1");
    when(jobTaskMapper.selectActiveByAssignedWorker("t1", "w1")).thenReturn(List.of(task1, task2));
    doThrow(new RuntimeException("retry failed"))
        .when(retryGovernanceService)
        .reclaimTask(eq("t1"), eq(301L), anyString());

    // 即使某个重试失败也不应抛出异常
    service.takeoverAfterDrainTimeout("t1", "w1");

    verify(retryGovernanceService).reclaimTask(eq("t1"), eq(302L), anyString());
  }

  // ── warmup ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("离线节点预热后状态翻转为在线")
  void warmup_flipsOfflineToOnline() {
    WorkerRegistryEntity registry = onlineWorker("t1", "w1")
        .withStatus(WorkerRegistryStatus.OFFLINE.code(), BatchDateTimeSupport.utcNow());
    WorkerRegistryEntity online = onlineWorker("t1", "w1");
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1")).thenReturn(registry, online);
    when(workerRegistryMapper.warmupIfOffline("t1", "w1")).thenReturn(1);

    WorkerRegistryEntity result = service.warmup("t1", "w1");

    assertThat(result.status()).isEqualTo(WorkerRegistryStatus.ONLINE.code());
    verify(workerRegistryMapper).warmupIfOffline("t1", "w1");
  }

  @Test
  @DisplayName("预热时状态已被并发修改则抛出业务异常")
  void shouldThrowOnConcurrentChange_whenWarmingUp() {
    WorkerRegistryEntity registry = onlineWorker("t1", "w1")
        .withStatus(WorkerRegistryStatus.OFFLINE.code(), BatchDateTimeSupport.utcNow());
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1")).thenReturn(registry);

    assertThatThrownBy(() -> service.warmup("t1", "w1")).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("节点已在线时预热幂等返回, 不重复执行状态更新")
  void warmup_idempotentWhenAlreadyOnline() {
    WorkerRegistryEntity registry = onlineWorker("t1", "w1");
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1")).thenReturn(registry);

    WorkerRegistryEntity result = service.warmup("t1", "w1");

    assertThat(result.status()).isEqualTo(WorkerRegistryStatus.ONLINE.code());
    verify(workerRegistryMapper, never()).warmupIfOffline(anyString(), anyString());
  }

  @Test
  @DisplayName("节点排空中时预热被拒绝且不执行状态更新")
  void warmup_rejectsDraining() {
    WorkerRegistryEntity registry = onlineWorker("t1", "w1")
        .withStatus(WorkerRegistryStatus.DRAINING.code(), BatchDateTimeSupport.utcNow());
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1")).thenReturn(registry);

    assertThatThrownBy(() -> service.warmup("t1", "w1")).isInstanceOf(BizException.class);
    verify(workerRegistryMapper, never()).warmupIfOffline(anyString(), anyString());
  }

  @Test
  @DisplayName("节点已下线时预热被拒绝且不执行状态更新")
  void warmup_rejectsDecommissioned() {
    WorkerRegistryEntity registry = onlineWorker("t1", "w1")
        .withStatus(WorkerRegistryStatus.DECOMMISSIONED.code(), BatchDateTimeSupport.utcNow());
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1")).thenReturn(registry);

    assertThatThrownBy(() -> service.warmup("t1", "w1")).isInstanceOf(BizException.class);
    verify(workerRegistryMapper, never()).warmupIfOffline(anyString(), anyString());
  }

  @Test
  @DisplayName("预热时节点未注册则抛出业务异常")
  void warmup_throwsWhenWorkerNotFound() {
    when(workerRegistryMapper.selectByTenantAndWorkerCode("t1", "w1")).thenReturn(null);
    assertThatThrownBy(() -> service.warmup("t1", "w1")).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("预热时租户标识为空则抛出业务异常")
  void warmup_throwsWhenTenantBlank() {
    assertThatThrownBy(() -> service.warmup("", "w1")).isInstanceOf(BizException.class);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static WorkerRegistryEntity onlineWorker(String tenantId, String workerCode) {
    return new WorkerRegistryEntity(
        null,
        tenantId,
        workerCode,
        null,
        null,
        null,
        WorkerRegistryStatus.ONLINE.code(),
        BatchDateTimeSupport.utcNow(),
        null,
        null,
        null,
        null);
  }
}
