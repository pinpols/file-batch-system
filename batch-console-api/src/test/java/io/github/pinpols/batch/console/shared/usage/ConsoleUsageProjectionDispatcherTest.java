package io.github.pinpols.batch.console.shared.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("使用量投影调度: 有界异步执行与队列满丢弃")
class ConsoleUsageProjectionDispatcherTest {

  @Test
  @DisplayName("提交的任务在调度线程执行")
  void shouldRunSubmittedProjection() {
    AtomicBoolean ran = new AtomicBoolean();
    ConsoleUsageProjectionDispatcher dispatcher =
        new ConsoleUsageProjectionDispatcher(Runnable::run);

    dispatcher.submit(() -> ran.set(true));

    assertThat(ran).isTrue();
  }

  @Test
  @DisplayName("队列满时丢弃派生写且不影响审计主流程")
  void shouldDropWithoutThrowing_whenExecutorRejects() {
    Executor rejecting = task -> {
      throw new RejectedExecutionException("queue full");
    };
    ConsoleUsageProjectionDispatcher dispatcher = new ConsoleUsageProjectionDispatcher(rejecting);

    assertThatCode(() -> dispatcher.submit(() -> {})).doesNotThrowAnyException();
  }
}
