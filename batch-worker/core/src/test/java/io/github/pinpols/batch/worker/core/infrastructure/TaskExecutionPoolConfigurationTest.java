package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.worker.core.config.WorkerExecutionTimeoutProperties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TaskExecutionPoolConfigurationTest {

  @Test
  void startFailsWhenPoolSizeIsSmallerThanMaxConcurrentTasks() {
    WorkerExecutionTimeoutProperties properties = new WorkerExecutionTimeoutProperties();
    properties.setPoolSize(2);
    TaskExecutionPool pool = new TaskExecutionPool(
        properties, new MockEnvironment().withProperty("batch.worker.max-concurrent-tasks", "4"));

    assertThatThrownBy(pool::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pool-size must be >= batch.worker.max-concurrent-tasks");
  }

  @Test
  void startSucceedsWhenPoolSizeMatchesMaxConcurrentTasks() {
    WorkerExecutionTimeoutProperties properties = new WorkerExecutionTimeoutProperties();
    properties.setPoolSize(4);
    TaskExecutionPool pool = new TaskExecutionPool(
        properties, new MockEnvironment().withProperty("batch.worker.max-concurrent-tasks", "4"));

    assertThatCode(pool::start).doesNotThrowAnyException();
    pool.shutdown();
  }

  @Test
  void submitRejectsWhenWorkerAndBoundedQueueAreBothOccupied() throws Exception {
    WorkerExecutionTimeoutProperties properties = new WorkerExecutionTimeoutProperties();
    properties.setPoolSize(1);
    TaskExecutionPool pool = new TaskExecutionPool(properties, null);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    pool.start();
    try {
      var running = pool.submit(() -> {
        started.countDown();
        release.await();
        return "running";
      });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      var queued = pool.submit(() -> "queued");

      assertThatThrownBy(() -> pool.submit(() -> "rejected"))
          .isInstanceOf(RejectedExecutionException.class);

      queued.cancel(false);
      // 工作线程尚未释放；取消必须立即腾出队列，而不是等待 dequeue。
      var replacement = pool.submit(() -> "replacement");
      assertThat(replacement.cancel(false)).isTrue();
      release.countDown();
      assertThat(running.get(2, TimeUnit.SECONDS)).isEqualTo("running");
    } finally {
      release.countDown();
      pool.shutdown();
    }
  }
}
