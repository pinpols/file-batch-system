package io.github.pinpols.batch.orchestrator.infrastructure.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.engine.DefaultScheduleForwarder;
import io.github.pinpols.batch.orchestrator.application.engine.ScheduleForwarderResult;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import io.github.pinpols.batch.orchestrator.config.OutboxProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.OutboxEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@ExtendWith(MockitoExtension.class)
@DisplayName("发件箱轮询调度器:验证熔断放行与拒绝,错误传播以及容器停机时的任务取消")
class OutboxPollSchedulerTest {

  @Mock
  private DefaultScheduleForwarder scheduleForwarder;

  @Mock
  private OutboxPublishCircuitBreaker outboxPublishCircuitBreaker;

  @Mock
  private BatchOrchestratorGovernanceProperties governance;

  @Mock
  private LockingTaskExecutor lockingTaskExecutor;

  @Mock
  private OrchestratorGracefulShutdown gracefulShutdown;

  @Mock
  private OutboxEventMapper outboxEventMapper;

  private OutboxPollScheduler scheduler;
  private SimpleMeterRegistry meterRegistry;
  private ThreadPoolTaskScheduler executor;
  private Throwable lockingFailure;

  @BeforeEach
  void setUp() {
    when(governance.outbox()).thenReturn(new OutboxProperties());
    executor = new ThreadPoolTaskScheduler();
    executor.setPoolSize(1);
    executor.setThreadNamePrefix("outbox-poll-test-");
    executor.initialize();
    meterRegistry = new SimpleMeterRegistry();
    scheduler = new OutboxPollScheduler(
        scheduleForwarder,
        outboxPublishCircuitBreaker,
        governance,
        lockingTaskExecutor,
        gracefulShutdown,
        outboxEventMapper,
        new io.github.pinpols.batch.orchestrator.infrastructure.sharding
            .StaticShardAssignmentProvider(governance.outbox()),
        meterRegistry,
        executor);
    // 不调用 onApplicationReady()，避免后台线程干扰单元测试
  }

  @AfterEach
  void tearDown() {
    scheduler.stopScheduling();
    executor.shutdown();
    meterRegistry.close();
  }

  @Test
  @DisplayName("熔断允许轮询时推进计划并把成功数量回写熔断器")
  void shouldAdvanceAndUpdateCircuitBreakerWhenAllowed() throws Throwable {
    stubLockExecution();
    when(outboxPublishCircuitBreaker.allowNow()).thenReturn(true);
    when(scheduleForwarder.advance(any())).thenReturn(ScheduleForwarderResult.of(3, 2, 1));

    scheduler.poll();

    ArgumentCaptor<SchedulePlan> planCaptor = ArgumentCaptor.forClass(SchedulePlan.class);
    verify(scheduleForwarder).advance(planCaptor.capture());
    assertThat(planCaptor.getValue()).isNotNull();
    verify(outboxPublishCircuitBreaker).onAdvanceResult(1);
  }

  @Test
  @DisplayName("熔断拒绝本轮轮询时跳过推进并累加跳过计数")
  void shouldSkipAdvanceWhenCircuitBreakerDeniesPolling() throws Throwable {
    stubLockExecution();
    when(outboxPublishCircuitBreaker.allowNow()).thenReturn(false);

    scheduler.poll();

    verify(scheduleForwarder, never()).advance(any());
    verify(outboxPublishCircuitBreaker, never()).onAdvanceResult(anyInt());
    // O1: 熔断跳过整轮时 batch.outbox.circuit.skipped_polls.total +1
    assertThat(meterRegistry
            .get("batch.outbox.circuit.skipped_polls.total")
            .counter()
            .count())
        .isEqualTo(1.0d);
  }

  @Test
  @DisplayName("锁内抛出内存溢出错误时原样向上传播,不被当作普通轮询失败吞掉")
  void shouldPropagateOutOfMemoryError_insteadOfTreatingItAsPollFailure() throws Throwable {
    stubLockExecution();
    OutOfMemoryError oom = new OutOfMemoryError("test oom");
    lockingFailure = oom;

    assertThatThrownBy(() -> scheduler.poll()).isSameAs(oom);
  }

  @Test
  @DisplayName("容器停机时取消队列中尚未执行的待调度轮询任务")
  void shouldCancelPendingPollWhenContainerStops() {
    scheduler.onApplicationReady(null);
    assertThat(executor.getScheduledThreadPoolExecutor().getQueue()).hasSize(1);

    scheduler.stopScheduling();

    assertThat(executor.getScheduledThreadPoolExecutor().getQueue())
        .allMatch(
            task -> task instanceof java.util.concurrent.Future<?> future && future.isCancelled());
  }

  private void stubLockExecution() throws Throwable {
    doAnswer(inv -> {
          if (lockingFailure != null) {
            throw lockingFailure;
          }
          inv.getArgument(0, LockingTaskExecutor.Task.class).call();
          return null;
        })
        .when(lockingTaskExecutor)
        .executeWithLock(any(LockingTaskExecutor.Task.class), any());
  }

  // 自适应间隔行为通过 OutboxForwarderE2eIT 验证
}
