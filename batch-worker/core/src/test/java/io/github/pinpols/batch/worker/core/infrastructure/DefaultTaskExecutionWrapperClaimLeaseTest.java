package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.worker.core.config.WorkerConcurrencyProperties;
import io.github.pinpols.batch.worker.core.config.WorkerExecutionTimeoutProperties;
import io.github.pinpols.batch.worker.core.domain.PulledTask;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.domain.WorkerExecutionResult;
import io.github.pinpols.batch.worker.core.support.StepExecutionAdapter;
import io.github.pinpols.batch.worker.core.support.TaskClaimItem;
import io.github.pinpols.batch.worker.core.support.TaskClaimResult;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import io.github.pinpols.batch.worker.core.support.TaskLeaseRenewItem;
import io.github.pinpols.batch.worker.core.support.TaskLeaseRenewResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@DisplayName("CLAIM 响应前的暂停时间计入租约年龄")
class DefaultTaskExecutionWrapperClaimLeaseTest {

  private static final long STALE_AFTER_MILLIS = 20_000L;
  private static final long LONG_PAUSE_NANOS = 21_000_000_000L;

  private final AtomicLong clock = new AtomicLong(1_000_000_000L);
  private final StepExecutionAdapter stepExecutionAdapter = mock(StepExecutionAdapter.class);
  private final TaskExecutionClient taskExecutionClient = mock(TaskExecutionClient.class);
  private final ActiveTaskLeaseRegistry activeTaskLeaseRegistry =
      new ActiveTaskLeaseRegistry(clock::get);
  private final WorkerExecutionTimeoutProperties timeoutProperties =
      new WorkerExecutionTimeoutProperties();
  private TaskExecutionPool executionPool;
  private ThreadPoolTaskScheduler watchdogScheduler;
  private DefaultTaskExecutionWrapper wrapper;

  @BeforeEach
  void setUp() {
    timeoutProperties.setPoolSize(1);
    timeoutProperties.setDefaultTimeoutSeconds(60L);
    timeoutProperties.setMaxTimeoutSeconds(120L);
    timeoutProperties.setCancelGraceSeconds(1L);
    WorkerConcurrencyProperties concurrencyProperties = new WorkerConcurrencyProperties();
    concurrencyProperties.setMaxConcurrentTasks(1);
    executionPool = new TaskExecutionPool(timeoutProperties, concurrencyProperties);
    executionPool.start();
    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
    when(meterRegistryProvider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());
    watchdogScheduler = new ThreadPoolTaskScheduler();
    watchdogScheduler.setPoolSize(1);
    watchdogScheduler.setThreadNamePrefix("claim-lease-test-watchdog-");
    watchdogScheduler.setDaemon(true);
    watchdogScheduler.initialize();
    wrapper = new DefaultTaskExecutionWrapper(
        stepExecutionAdapter,
        taskExecutionClient,
        activeTaskLeaseRegistry,
        executionPool,
        timeoutProperties,
        meterRegistryProvider,
        watchdogScheduler,
        10_000L);
  }

  @AfterEach
  void tearDown() {
    wrapper.shutdownWatchdog();
    watchdogScheduler.shutdown();
    executionPool.shutdown();
  }

  @Test
  @DisplayName("单条 CLAIM 调用耗时超过租约窗口时先同步确认再执行业务")
  void shouldIncludeClaimLatencyInLeaseAge_whenClaimingSingleTask() {
    EffectiveTaskConfig config = effectiveConfig(42L);
    when(taskExecutionClient.claim("tenant-1", 42L, "worker-1")).thenAnswer(invocation -> {
      clock.addAndGet(LONG_PAUSE_NANOS);
      return Optional.of(config);
    });
    when(taskExecutionClient.renewLeasesBatch(
            List.of(new TaskLeaseRenewItem("tenant-1", 42L, "worker-1", "inv-42"))))
        .thenReturn(Map.of(42L, new TaskLeaseRenewResult(42L, true, false)));
    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenReturn(StepExecutionResponse.successResponse());

    EffectiveTaskConfig claimed = wrapper.claim("tenant-1", 42L, "worker-1").orElseThrow();
    WorkerExecutionResult result = wrapper.execute(pulledTask(claimed));

    assertThat(result.success()).isTrue();
    InOrder order = inOrder(taskExecutionClient, stepExecutionAdapter);
    order.verify(taskExecutionClient).claim("tenant-1", 42L, "worker-1");
    order
        .verify(taskExecutionClient)
        .renewLeasesBatch(List.of(new TaskLeaseRenewItem("tenant-1", 42L, "worker-1", "inv-42")));
    order.verify(stepExecutionAdapter).execute(any(StepExecutionRequest.class));
  }

  @Test
  @DisplayName("批量 CLAIM 的网络等待时间计入每个成功认领项的租约年龄")
  void shouldIncludeClaimLatencyForEveryLease_whenClaimingBatch() {
    List<TaskClaimItem> items = List.of(
        new TaskClaimItem("tenant-1", 42L, "worker-1"),
        new TaskClaimItem("tenant-1", 43L, "worker-1"));
    when(taskExecutionClient.claimBatch(items)).thenAnswer(invocation -> {
      clock.addAndGet(LONG_PAUSE_NANOS);
      return List.of(
          new TaskClaimResult(42L, true, effectiveConfig(42L)),
          new TaskClaimResult(43L, true, effectiveConfig(43L)));
    });

    List<TaskClaimResult> result = wrapper.claimBatch(items);

    assertThat(result).hasSize(2);
    assertThat(activeTaskLeaseRegistry.snapshot())
        .extracting(lease -> lease.getTaskId())
        .containsExactlyInAnyOrder("42", "43");
    assertThat(activeTaskLeaseRegistry.isRenewalDue("42", STALE_AFTER_MILLIS)).isTrue();
    assertThat(activeTaskLeaseRegistry.isRenewalDue("43", STALE_AFTER_MILLIS)).isTrue();
  }

  private static EffectiveTaskConfig effectiveConfig(Long taskId) {
    return new EffectiveTaskConfig(
        "tenant-1",
        taskId,
        100L,
        200L,
        "INST-" + taskId,
        "JOB",
        "IMPORT",
        1,
        "IMPORT",
        "HIGH",
        "business-key",
        "idem-" + taskId,
        "{}",
        "trace-" + taskId,
        "FULL",
        null,
        null,
        "NONE",
        0,
        60,
        1,
        1,
        "JOB:2026-10-16:1",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "inv-" + taskId,
        List.of(),
        null);
  }

  private static PulledTask pulledTask(EffectiveTaskConfig config) {
    PulledTask task = new PulledTask();
    task.setTaskId(String.valueOf(config.taskId()));
    task.setTenantId(config.tenantId());
    task.setWorkerId("worker-1");
    task.setTaskType(config.taskType());
    task.setJobCode(config.jobCode());
    task.setTraceId(config.traceId());
    task.setBusinessKey(config.businessKey());
    task.setJobInstanceId(config.jobInstanceId());
    task.setJobPartitionId(config.jobPartitionId());
    task.setTaskSeq(config.taskSeq());
    task.setIdempotencyKey(config.idempotencyKey());
    task.setPayload(config.payload());
    task.setPartitionInvocationId(config.partitionInvocationId());
    task.setTimeoutSeconds(config.timeoutSeconds());
    return task;
  }
}
