package io.github.pinpols.batch.common.spi.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskResultTest {

  @Test
  void okHelpers() {
    assertThat(TaskResult.ok().success()).isTrue();
    assertThat(TaskResult.ok().message()).isEqualTo("ok");
    assertThat(TaskResult.ok().output()).isEmpty();
    assertThat(TaskResult.ok("done").message()).isEqualTo("done");
    assertThat(TaskResult.ok(Map.of("k", "v")).output()).containsEntry("k", "v");
  }

  @Test
  void failHelpers() {
    TaskResult fromMsg = TaskResult.fail("oops");
    assertThat(fromMsg.success()).isFalse();
    assertThat(fromMsg.message()).isEqualTo("oops");
    assertThat(fromMsg.error()).isNull();

    RuntimeException ex = new RuntimeException("boom");
    TaskResult fromEx = TaskResult.fail(ex);
    assertThat(fromEx.success()).isFalse();
    assertThat(fromEx.message()).isEqualTo("boom");
    assertThat(fromEx.error()).isSameAs(ex);
  }

  @Test
  void failFromExWithoutMessageUsesSimpleName() {
    TaskResult r = TaskResult.fail(new IllegalStateException());
    assertThat(r.message()).isEqualTo("IllegalStateException");
  }

  @Test
  void failWithMessageAndError() {
    Throwable t = new IllegalArgumentException();
    TaskResult r = TaskResult.fail("ctx", t);
    assertThat(r.message()).isEqualTo("ctx");
    assertThat(r.error()).isSameAs(t);
  }

  @Test
  void failRequiresNonNullArgs() {
    assertThatThrownBy(() -> TaskResult.fail((String) null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> TaskResult.fail((Throwable) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void outputDefaultsToEmptyMap() {
    TaskResult r = new TaskResult(true, "ok", null, null);
    assertThat(r.output()).isEmpty();
  }
}
