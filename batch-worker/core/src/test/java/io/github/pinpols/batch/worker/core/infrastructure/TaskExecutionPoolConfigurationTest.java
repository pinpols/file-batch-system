package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.worker.core.config.WorkerExecutionTimeoutProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TaskExecutionPoolConfigurationTest {

  @Test
  void startFailsWhenPoolSizeIsSmallerThanMaxConcurrentTasks() {
    WorkerExecutionTimeoutProperties properties = new WorkerExecutionTimeoutProperties();
    properties.setPoolSize(2);
    TaskExecutionPool pool =
        new TaskExecutionPool(
            properties,
            new MockEnvironment().withProperty("batch.worker.max-concurrent-tasks", "4"));

    assertThatThrownBy(pool::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pool-size must be >= batch.worker.max-concurrent-tasks");
  }

  @Test
  void startSucceedsWhenPoolSizeMatchesMaxConcurrentTasks() {
    WorkerExecutionTimeoutProperties properties = new WorkerExecutionTimeoutProperties();
    properties.setPoolSize(4);
    TaskExecutionPool pool =
        new TaskExecutionPool(
            properties,
            new MockEnvironment().withProperty("batch.worker.max-concurrent-tasks", "4"));

    assertThatCode(pool::start).doesNotThrowAnyException();
    pool.shutdown();
  }
}
