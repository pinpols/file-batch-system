package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.model.WorkerRouteModel;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.scheduler.ConcurrencyLimiter;
import io.github.pinpols.batch.orchestrator.application.scheduler.PartitionThrottle;
import io.github.pinpols.batch.orchestrator.application.scheduler.PriorityScheduler;
import io.github.pinpols.batch.orchestrator.application.scheduler.ResourceQueueManager;
import io.github.pinpols.batch.orchestrator.application.scheduler.WorkerSelector;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceAdmissionAction;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * 守护 ResourceScheduler 的 short-circuit pipeline:
 *
 * <ul>
 *   <li>concurrency block → 返 dispatchable=false,后续 partition/worker check 不再调
 *   <li>partition block → 同样 short-circuit
 *   <li>worker 不可用 → blocked,reasonCode=NO_AVAILABLE_WORKER
 *   <li>全通过 → dispatchable=true + 决策字段齐全
 *   <li>limiter reasonCode 含 _DEGRADED → 优先级降为最低 LOW
 * </ul>
 *
 * <p>checkBatchWindow 走 service 内部 private 方法,与 BatchWindowEntity 紧耦合,留集成测覆盖。
 */
class DefaultResourceSchedulerTest {

  @Mock private ResourceQueueManager queueManager;
  @Mock private ConcurrencyLimiter concurrencyLimiter;
  @Mock private PartitionThrottle partitionThrottle;
  @Mock private WorkerSelector workerSelector;
  @Mock private PriorityScheduler priorityScheduler;
  @Mock private OrchestratorConfigCacheService configCacheService;
  @Mock private JobInstanceMapper jobInstanceMapper;
  @Mock private JobPartitionMapper jobPartitionMapper;
  @Mock private BatchTimezoneProvider timezoneProvider;
  @Mock private BatchDateTimeSupport dateTimeSupport;

  private DefaultResourceScheduler scheduler;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    scheduler =
        new DefaultResourceScheduler(
            queueManager,
            concurrencyLimiter,
            partitionThrottle,
            workerSelector,
            priorityScheduler,
            configCacheService,
            jobInstanceMapper,
            jobPartitionMapper,
            timezoneProvider,
            dateTimeSupport);

    when(queueManager.resolveQueue(any())).thenReturn(null);
    when(priorityScheduler.resolvePriority(any(), any())).thenReturn(5);
    when(priorityScheduler.resolvePriorityBand(any())).thenReturn("MEDIUM");
  }

  private ResourceSchedulingRequest req() {
    ResourceSchedulingRequest r = new ResourceSchedulingRequest();
    r.setTenantId("ta");
    r.setJobCode("j1");
    r.setQueueCode("default");
    r.setWorkerGroup("default");
    return r;
  }

  @Test
  @DisplayName("concurrency block → dispatchable=false, short-circuit (不再调 partition/worker)")
  void concurrencyBlockShortCircuits() {
    when(concurrencyLimiter.check(any(), any()))
        .thenReturn(ResourceCheck.waitForCapacity("CONCURRENCY_LIMIT", "max running reached"));

    ResourceSchedulingDecision d = scheduler.schedule(req());
    assertThat(d.getAdmissionAction()).isEqualTo(ResourceAdmissionAction.DEFER);
    assertThat(d.isDispatchable()).isFalse();
    assertThat(d.getReasonCode()).isEqualTo("CONCURRENCY_LIMIT");
    verify(partitionThrottle, never()).check(any(), any());
    verify(workerSelector, never()).select(any(), any(), any());
  }

  @Test
  @DisplayName("partition block → dispatchable=false, short-circuit (不再调 worker)")
  void partitionBlockShortCircuits() {
    when(concurrencyLimiter.check(any(), any())).thenReturn(ResourceCheck.allow());
    when(partitionThrottle.check(any(), any()))
        .thenReturn(ResourceCheck.waitForCapacity("PARTITION_THROTTLE", "throttled"));

    ResourceSchedulingDecision d = scheduler.schedule(req());
    assertThat(d.getAdmissionAction()).isEqualTo(ResourceAdmissionAction.DEFER);
    assertThat(d.isDispatchable()).isFalse();
    assertThat(d.getReasonCode()).isEqualTo("PARTITION_THROTTLE");
    verify(workerSelector, never()).select(any(), any(), any());
  }

  @Test
  @DisplayName("worker 不可用 → blocked + reasonCode=NO_AVAILABLE_WORKER")
  void noAvailableWorkerBlocks() {
    when(concurrencyLimiter.check(any(), any())).thenReturn(ResourceCheck.allow());
    when(partitionThrottle.check(any(), any())).thenReturn(ResourceCheck.allow());
    when(workerSelector.select(any(), any(), any())).thenReturn(null);

    ResourceSchedulingDecision d = scheduler.schedule(req());
    assertThat(d.getAdmissionAction()).isEqualTo(ResourceAdmissionAction.DEFER);
    assertThat(d.isDispatchable()).isFalse();
    assertThat(d.getReasonCode()).isEqualTo("NO_AVAILABLE_WORKER");
  }

  @Test
  @DisplayName("worker.available=false → 同样 blocked")
  void workerUnavailableBlocks() {
    when(concurrencyLimiter.check(any(), any())).thenReturn(ResourceCheck.allow());
    when(partitionThrottle.check(any(), any())).thenReturn(ResourceCheck.allow());
    WorkerRouteModel route = new WorkerRouteModel();
    route.setAvailable(false);
    when(workerSelector.select(any(), any(), any())).thenReturn(route);

    ResourceSchedulingDecision d = scheduler.schedule(req());
    assertThat(d.isDispatchable()).isFalse();
    assertThat(d.getReasonCode()).isEqualTo("NO_AVAILABLE_WORKER");
  }

  @Test
  @DisplayName("全通过 → dispatchable=true + 决策字段齐全")
  void allPassReturnsDispatchable() {
    when(concurrencyLimiter.check(any(), any())).thenReturn(ResourceCheck.allow());
    when(partitionThrottle.check(any(), any())).thenReturn(ResourceCheck.allow());
    WorkerRouteModel route = new WorkerRouteModel();
    route.setAvailable(true);
    route.setWorkerCode("w-1");
    when(workerSelector.select(any(), any(), any())).thenReturn(route);

    ResourceSchedulingDecision d = scheduler.schedule(req());
    assertThat(d.getAdmissionAction()).isEqualTo(ResourceAdmissionAction.ACCEPT);
    assertThat(d.isDispatchable()).isTrue();
    assertThat(d.getPriority()).isEqualTo(5);
    assertThat(d.getPriorityBand()).isEqualTo("MEDIUM");
    assertThat(d.getRoute()).isSameAs(route);
    assertThat(d.getPartitionStatus()).isEqualTo("CREATED");
    assertThat(d.getTaskStatus()).isEqualTo("CREATED");
  }

  @Test
  @DisplayName("blocker reasonCode 含 _DEGRADED 后缀 → 决策 priority 降到 1 / band=LOW (fairness 沉到队尾)")
  void degradedLowersPriorityToMinimum() {
    when(concurrencyLimiter.check(any(), any()))
        .thenReturn(ResourceCheck.waitForCapacity("CONCURRENCY_LIMIT_DEGRADED", "degraded"));

    ResourceSchedulingDecision d = scheduler.schedule(req());
    assertThat(d.isDispatchable()).isFalse();
    assertThat(d.getPriority()).isEqualTo(1);
    assertThat(d.getPriorityBand()).isEqualTo("LOW");
  }

  @Test
  @DisplayName("failFast block → admissionAction=REJECT")
  void failFastBlockBecomesRejectAdmission() {
    when(concurrencyLimiter.check(any(), any()))
        .thenReturn(ResourceCheck.reject("TENANT_JOB_LIMIT", "tenant quota exceeded"));

    ResourceSchedulingDecision d = scheduler.schedule(req());

    assertThat(d.getAdmissionAction()).isEqualTo(ResourceAdmissionAction.REJECT);
    assertThat(d.isFailFast()).isTrue();
    assertThat(d.getReasonCode()).isEqualTo("TENANT_JOB_LIMIT");
  }

  @Test
  @DisplayName("队列字段 windowCode 为空 → 跳过 batch window 检查,允许后续 pipeline")
  void emptyWindowCodeSkipsWindowCheck() {
    when(concurrencyLimiter.check(any(), any())).thenReturn(ResourceCheck.allow());
    when(partitionThrottle.check(any(), any())).thenReturn(ResourceCheck.allow());
    WorkerRouteModel route = new WorkerRouteModel();
    route.setAvailable(true);
    when(workerSelector.select(any(), any(), any())).thenReturn(route);

    ResourceSchedulingRequest r = req();
    r.setWindowCode(""); // 空 window
    ResourceSchedulingDecision d = scheduler.schedule(r);
    assertThat(d.isDispatchable()).isTrue();
    // 不调 configCacheService 查 window
    verify(configCacheService, never()).findEnabledBatchWindow(anyString(), anyString());
  }
}
