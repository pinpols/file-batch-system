package io.github.pinpols.batch.common.spi.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("任务结果:成功与失败工厂方法的取值约定及空参校验")
class TaskResultTest {

  @Test
  @DisplayName("成功工厂:成功标记为真, 文案与自定义输出按入参保留, 默认输出为空")
  void shouldBuildSuccessResults_whenOkHelpersUsed() {
    assertThat(TaskResult.ok().success()).isTrue();
    assertThat(TaskResult.ok().message()).isEqualTo("ok");
    assertThat(TaskResult.ok().output()).isEmpty();
    assertThat(TaskResult.ok("done").message()).isEqualTo("done");
    assertThat(TaskResult.ok(Map.of("k", "v")).output()).containsEntry("k", "v");
  }

  @Test
  @DisplayName("失败工厂:成功标记为假, 文案取入参, 异常对象按入参保留或为空")
  void shouldBuildFailureResults_whenFailHelpersUsed() {
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
  @DisplayName("异常无描述信息:失败文案回退为异常类简单名")
  void shouldUseExceptionSimpleName_whenMessageAbsent() {
    TaskResult r = TaskResult.fail(new IllegalStateException());
    assertThat(r.message()).isEqualTo("IllegalStateException");
  }

  @Test
  @DisplayName("同时给出文案与异常:两者均被保留")
  void shouldKeepMessageAndError_whenBothProvided() {
    Throwable t = new IllegalArgumentException();
    TaskResult r = TaskResult.fail("ctx", t);
    assertThat(r.message()).isEqualTo("ctx");
    assertThat(r.error()).isSameAs(t);
  }

  @Test
  @DisplayName("失败工厂入参为空:抛出空指针异常")
  void shouldRejectNullArguments_whenBuildingFailure() {
    assertThatThrownBy(() -> TaskResult.fail((String) null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> TaskResult.fail((Throwable) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("未提供输出:默认输出为空集合")
  void shouldDefaultOutputToEmptyMap_whenOutputAbsent() {
    TaskResult r = new TaskResult(true, "ok", null, null);
    assertThat(r.output()).isEmpty();
  }
}
