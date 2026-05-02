package com.example.batch.orchestrator.infrastructure.retry;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.batch.orchestrator.application.service.governance.RetryGovernanceService;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetryScheduleSchedulerTest {

  @Mock private RetryGovernanceService retryGovernanceService;

  @Mock private OrchestratorGracefulShutdown gracefulShutdown;

  private RetryScheduleScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler = new RetryScheduleScheduler(retryGovernanceService, gracefulShutdown);
  }

  @Test
  void shouldDispatchDueRetries() {
    scheduler.poll();

    verify(retryGovernanceService).dispatchDueRetries();
  }

  @Test
  void shouldDispatchOnlyOnceWhenPollIsCalledConcurrently() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              entered.countDown();
              release.await();
              return null;
            })
        .when(retryGovernanceService)
        .dispatchDueRetries();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = pool.submit(scheduler::poll);
      entered.await();

      Future<?> second = pool.submit(scheduler::poll);
      // 确保第二个 poll() 在第一个仍阻塞在 dispatch 内部时执行（并在 CAS 上提前返回）；
      // 否则第一个可能先完成并清除 `running`，导致第二个 poll 也触发了 dispatch。
      second.get(5, TimeUnit.SECONDS);
      release.countDown();

      first.get(5, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    verify(retryGovernanceService, times(1)).dispatchDueRetries();
  }
}
