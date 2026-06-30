package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.scheduler.ConcurrencyLimiter;
import io.github.pinpols.batch.orchestrator.application.scheduler.PartitionThrottle;
import io.github.pinpols.batch.orchestrator.application.scheduler.PriorityScheduler;
import io.github.pinpols.batch.orchestrator.application.scheduler.ResourceQueueManager;
import io.github.pinpols.batch.orchestrator.application.scheduler.WorkerSelector;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchWindowEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V5-P2-2 验证：业务日历 / batch_window 门禁挂起任务的逻辑。
 *
 * <p>覆盖 3 个分支：
 *
 * <ul>
 *   <li>当前时间 in-window → allow（schedule 不被门禁挡住）
 *   <li>当前时间 out-of-window + outOfWindowAction=WAIT → waitForCapacity（partition 留 WAITING）
 *   <li>当前时间 out-of-window + outOfWindowAction=FAIL → reject failFast=true
 * </ul>
 */
class BatchWindowGateTest {

  private OrchestratorConfigCacheService configCacheService;
  private DefaultResourceScheduler scheduler;
  private BatchTimezoneProvider timezoneProvider;

  @BeforeEach
  void setUp() {
    configCacheService = mock(OrchestratorConfigCacheService.class);
    timezoneProvider = mock(BatchTimezoneProvider.class);
    when(timezoneProvider.resolveOrDefault(any())).thenReturn(ZoneId.of("Asia/Shanghai"));

    ResourceQueueManager queueManager = mock(ResourceQueueManager.class);
    when(queueManager.resolveQueue(any())).thenReturn(null);

    PriorityScheduler prioritySched = mock(PriorityScheduler.class);
    when(prioritySched.resolvePriority(any(), any())).thenReturn(5);
    when(prioritySched.resolvePriorityBand(any())).thenReturn("NORMAL");

    // 关键：让 batch_window 之后的门禁（concurrency / partitionThrottle）都 allow，
    // 这样 in-window 路径才能跑到底；否则 NPE on `concurrencyCheck.allowed()`。
    // 用 doReturn().when() 形态绕开 Mockito strict-stubs 对 null arg 的 issue。
    ConcurrencyLimiter concLimit = mock(ConcurrencyLimiter.class);
    doReturn(ResourceCheck.allow()).when(concLimit).check(any(), any());
    PartitionThrottle partThrottle = mock(PartitionThrottle.class);
    doReturn(ResourceCheck.allow()).when(partThrottle).check(any(), any());

    scheduler =
        new DefaultResourceScheduler(
            queueManager,
            concLimit,
            partThrottle,
            mockWorkerSelector(),
            prioritySched,
            configCacheService,
            mock(JobInstanceMapper.class),
            mock(JobPartitionMapper.class),
            timezoneProvider,
            new BatchDateTimeSupport(Clock.systemUTC(), timezoneProvider),
            new ResourceSchedulerProperties());
  }

  /** in-window：当前时间在窗口内，schedule 不被门禁挡住（allow 路径，可能后续被并发 / worker 等其他门禁挡，但不是 batch_window 挡的）。 */
  @Test
  void shouldAllow_whenCurrentTimeIsWithinWindow() {
    LocalTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalTime();
    LocalTime start = now.minusHours(1);
    LocalTime end = now.plusHours(1);
    when(configCacheService.findEnabledBatchWindow(anyString(), anyString()))
        .thenReturn(window("WAIT", start, end));

    ResourceSchedulingDecision decision = scheduler.schedule(request());

    // batch_window 不挡 → 后续 concurrency / worker 检查可能挡，但 reasonCode 不是 OUT_OF_WINDOW
    assertThat(decision.getReasonCode()).isNotEqualTo("OUT_OF_WINDOW");
    assertThat(decision.getReasonCode()).isNotEqualTo("OUT_OF_WINDOW_WAIT");
  }

  /** out-of-window + WAIT：partition 状态保持 WAITING，等下次 tick 在 window 内重试。 */
  @Test
  void shouldWaitForCapacity_whenOutOfWindow_andActionIsWAIT() {
    // 故意构造一个 1 分钟前刚结束的窗口（绝对在 out-of-window）
    LocalTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalTime();
    LocalTime start = now.minusHours(2);
    LocalTime end = now.minusMinutes(1);
    when(configCacheService.findEnabledBatchWindow(anyString(), anyString()))
        .thenReturn(window("WAIT", start, end));

    ResourceSchedulingDecision decision = scheduler.schedule(request());

    assertThat(decision.getReasonCode()).isEqualTo("OUT_OF_WINDOW_WAIT");
    assertThat(decision.getReasonMessage()).isEqualTo("waiting for batch window");
    assertThat(decision.isFailFast()).isFalse();
  }

  /** out-of-window + FAIL：reject + failFast=true，应 fail-fast 不再等待。 */
  @Test
  void shouldRejectFailFast_whenOutOfWindow_andActionIsFAIL() {
    LocalTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalTime();
    LocalTime start = now.minusHours(2);
    LocalTime end = now.minusMinutes(1);
    when(configCacheService.findEnabledBatchWindow(anyString(), anyString()))
        .thenReturn(window("FAIL", start, end));

    ResourceSchedulingDecision decision = scheduler.schedule(request());

    assertThat(decision.getReasonCode()).isEqualTo("OUT_OF_WINDOW");
    assertThat(decision.getReasonMessage())
        .isEqualTo("current execution time is outside batch window");
    assertThat(decision.isFailFast()).isTrue();
  }

  /** 未配 windowCode → 跳过门禁直接 allow。 */
  @Test
  void shouldAllow_whenWindowCodeIsMissing() {
    ResourceSchedulingRequest req = request();
    req.setWindowCode(null);

    ResourceSchedulingDecision decision = scheduler.schedule(req);

    assertThat(decision.getReasonCode()).isNotEqualTo("OUT_OF_WINDOW");
    assertThat(decision.getReasonCode()).isNotEqualTo("OUT_OF_WINDOW_WAIT");
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static ResourceSchedulingRequest request() {
    ResourceSchedulingRequest r = new ResourceSchedulingRequest();
    r.setTenantId("tenant-window-test");
    r.setJobCode("JOB_X");
    r.setQueueCode("default-queue");
    r.setWindowCode("daily-window");
    r.setWorkerGroup("import");
    r.setWorkerType("IMPORT");
    return r;
  }

  private static BatchWindowEntity window(
      String outOfWindowAction, LocalTime start, LocalTime end) {
    return new BatchWindowEntity(
        1L,
        "tenant-window-test",
        "daily-window",
        "Daily Window",
        "Asia/Shanghai",
        start,
        end,
        "FINISH_RUNNING",
        outOfWindowAction,
        false,
        true);
  }

  private static WorkerSelector mockWorkerSelector() {
    return mock(WorkerSelector.class);
  }
}
