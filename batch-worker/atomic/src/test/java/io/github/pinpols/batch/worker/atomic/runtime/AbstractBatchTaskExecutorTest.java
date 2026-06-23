package io.github.pinpols.batch.worker.atomic.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.spi.task.TaskCapability;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** {@link AbstractBatchTaskExecutor} 模板方法执行序 + 异常路径 + cleanup 必跑测试。 */
class AbstractBatchTaskExecutorTest {

  private static TaskContext ctx() {
    return new TaskContext("tx", "j", "ti-1", "w-1", Map.of(), Map.of());
  }

  @Test
  void successPathRunsAllHooksInOrder() {
    List<String> log = new java.util.ArrayList<>();
    AbstractBatchTaskExecutor exec =
        new AbstractBatchTaskExecutor() {
          @Override
          public String taskType() {
            return "tt";
          }

          @Override
          public TaskCapability capability() {
            return TaskCapability.of();
          }

          @Override
          protected void validate(TaskContext c) {
            log.add("validate");
          }

          @Override
          protected void before(TaskContext c) {
            log.add("before");
          }

          @Override
          protected TaskResult doExecute(TaskContext c) {
            log.add("doExecute");
            return TaskResult.ok("done", Map.of("rows", 5));
          }

          @Override
          protected void after(TaskContext c, TaskResult r) {
            log.add("after");
          }

          @Override
          protected void cleanup(TaskContext c) {
            log.add("cleanup");
          }
        };

    TaskResult r = exec.execute(ctx());

    assertThat(r.success()).isTrue();
    assertThat(log).containsExactly("validate", "before", "doExecute", "after", "cleanup");
  }

  @Test
  void doExecuteExceptionStillRunsCleanup() {
    AtomicInteger cleanupCount = new AtomicInteger();
    AbstractBatchTaskExecutor exec =
        new AbstractBatchTaskExecutor() {
          @Override
          public String taskType() {
            return "tt";
          }

          @Override
          public TaskCapability capability() {
            return TaskCapability.of();
          }

          @Override
          protected TaskResult doExecute(TaskContext c) {
            throw new RuntimeException("biz boom");
          }

          @Override
          protected void cleanup(TaskContext c) {
            cleanupCount.incrementAndGet();
          }
        };

    TaskResult r = exec.execute(ctx());

    assertThat(r.success()).isFalse();
    assertThat(r.error()).isInstanceOf(RuntimeException.class).hasMessage("biz boom");
    assertThat(cleanupCount.get()).isEqualTo(1);
  }

  @Test
  void validateFailureSkipsBeforeAndCleanup() {
    AtomicInteger cleanupCount = new AtomicInteger();
    AtomicInteger beforeCount = new AtomicInteger();
    AbstractBatchTaskExecutor exec =
        new AbstractBatchTaskExecutor() {
          @Override
          public String taskType() {
            return "tt";
          }

          @Override
          public TaskCapability capability() {
            return TaskCapability.of();
          }

          @Override
          protected void validate(TaskContext c) {
            throw new IllegalArgumentException("bad input");
          }

          @Override
          protected void before(TaskContext c) {
            beforeCount.incrementAndGet();
          }

          @Override
          protected TaskResult doExecute(TaskContext c) {
            throw new AssertionError("must not run");
          }

          @Override
          protected void cleanup(TaskContext c) {
            cleanupCount.incrementAndGet();
          }
        };

    TaskResult r = exec.execute(ctx());

    assertThat(r.success()).isFalse();
    assertThat(beforeCount.get()).isZero();
    assertThat(cleanupCount.get()).isZero(); // before 未跑,cleanup 不该跑
  }

  @Test
  void beforeFailureSkipsDoExecuteAndCleanup() {
    // before 抛异常时 started 仍为 false(在 started=true 之前抛),cleanup 不应跑;
    // 与 validate 失败一样落在 started=false 分支,但 before 确实被调用过。
    AtomicInteger beforeCount = new AtomicInteger();
    AtomicInteger doExecuteCount = new AtomicInteger();
    AtomicInteger cleanupCount = new AtomicInteger();
    AbstractBatchTaskExecutor exec =
        new AbstractBatchTaskExecutor() {
          @Override
          public String taskType() {
            return "tt";
          }

          @Override
          public TaskCapability capability() {
            return TaskCapability.of();
          }

          @Override
          protected void before(TaskContext c) {
            beforeCount.incrementAndGet();
            throw new IllegalStateException("acquire failed");
          }

          @Override
          protected TaskResult doExecute(TaskContext c) {
            doExecuteCount.incrementAndGet();
            return TaskResult.ok();
          }

          @Override
          protected void cleanup(TaskContext c) {
            cleanupCount.incrementAndGet();
          }
        };

    TaskResult r = exec.execute(ctx());

    assertThat(r.success()).isFalse();
    assertThat(r.error()).isInstanceOf(IllegalStateException.class).hasMessage("acquire failed");
    assertThat(beforeCount.get()).isEqualTo(1); // before 跑过
    assertThat(doExecuteCount.get()).isZero(); // doExecute 未跑
    assertThat(cleanupCount.get()).isZero(); // started=false → cleanup 不跑
  }

  @Test
  void nullResultTreatedAsFailure() {
    AbstractBatchTaskExecutor exec =
        new AbstractBatchTaskExecutor() {
          @Override
          public String taskType() {
            return "tt";
          }

          @Override
          public TaskCapability capability() {
            return TaskCapability.of();
          }

          @Override
          protected TaskResult doExecute(TaskContext c) {
            return null;
          }
        };

    TaskResult r = exec.execute(ctx());
    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("returned null");
  }

  @Test
  void cleanupExceptionDoesNotMaskOriginalResult() {
    AbstractBatchTaskExecutor exec =
        new AbstractBatchTaskExecutor() {
          @Override
          public String taskType() {
            return "tt";
          }

          @Override
          public TaskCapability capability() {
            return TaskCapability.of();
          }

          @Override
          protected TaskResult doExecute(TaskContext c) {
            return TaskResult.ok("ok", Map.of());
          }

          @Override
          protected void cleanup(TaskContext c) {
            throw new RuntimeException("cleanup boom");
          }
        };

    TaskResult r = exec.execute(ctx());
    assertThat(r.success()).isTrue(); // 原 success 不被 cleanup 异常覆盖
    assertThat(r.message()).isEqualTo("ok");
  }
}
