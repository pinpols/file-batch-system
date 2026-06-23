package io.github.pinpols.batch.orchestrator.infrastructure.mq;

import static org.assertj.core.api.Assertions.assertThat;
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
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@ExtendWith(MockitoExtension.class)
class OutboxPollSchedulerTest {

  @Mock private DefaultScheduleForwarder scheduleForwarder;

  @Mock private OutboxPublishCircuitBreaker outboxPublishCircuitBreaker;

  @Mock private BatchOrchestratorGovernanceProperties governance;

  @Mock private LockingTaskExecutor lockingTaskExecutor;

  @Mock private OrchestratorGracefulShutdown gracefulShutdown;

  @Mock private OutboxEventMapper outboxEventMapper;

  private OutboxPollScheduler scheduler;

  @BeforeEach
  void setUp() throws Throwable {
    when(governance.outbox()).thenReturn(new OutboxProperties());
    doAnswer(
            inv -> {
              inv.getArgument(0, LockingTaskExecutor.Task.class).call();
              return null;
            })
        .when(lockingTaskExecutor)
        .executeWithLock(any(LockingTaskExecutor.Task.class), any());
    ThreadPoolTaskScheduler executor = new ThreadPoolTaskScheduler();
    executor.setPoolSize(1);
    executor.setThreadNamePrefix("outbox-poll-test-");
    executor.initialize();
    scheduler =
        new OutboxPollScheduler(
            scheduleForwarder,
            outboxPublishCircuitBreaker,
            governance,
            lockingTaskExecutor,
            gracefulShutdown,
            outboxEventMapper,
            new io.github.pinpols.batch.orchestrator.infrastructure.sharding
                .StaticShardAssignmentProvider(governance.outbox()),
            executor);
    // 不调用 onApplicationReady()，避免后台线程干扰单元测试
  }

  @Test
  void shouldAdvanceAndUpdateCircuitBreakerWhenAllowed() {
    when(outboxPublishCircuitBreaker.allowNow()).thenReturn(true);
    when(scheduleForwarder.advance(any())).thenReturn(ScheduleForwarderResult.of(3, 2, 1));

    scheduler.poll();

    ArgumentCaptor<SchedulePlan> planCaptor = ArgumentCaptor.forClass(SchedulePlan.class);
    verify(scheduleForwarder).advance(planCaptor.capture());
    assertThat(planCaptor.getValue()).isNotNull();
    verify(outboxPublishCircuitBreaker).onAdvanceResult(1);
  }

  @Test
  void shouldSkipAdvanceWhenCircuitBreakerDeniesPolling() {
    when(outboxPublishCircuitBreaker.allowNow()).thenReturn(false);

    scheduler.poll();

    verify(scheduleForwarder, never()).advance(any());
    verify(outboxPublishCircuitBreaker, never()).onAdvanceResult(anyInt());
  }

  // 自适应间隔行为通过 OutboxForwarderE2eIT 验证
}
