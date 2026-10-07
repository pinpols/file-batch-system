package io.github.pinpols.batch.sdk.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SdkTaskResult — 成功与失败结果构造、异常兜底与输出拷贝")
class SdkTaskResultTest {

  @Test
  @DisplayName("成功结果工厂带默认消息、自定义消息与输出")
  void shouldBuildSuccessResult_withMessageAndOutput() {
    assertThat(SdkTaskResult.ok().success()).isTrue();
    assertThat(SdkTaskResult.ok().message()).isEqualTo("ok");
    assertThat(SdkTaskResult.ok("done").message()).isEqualTo("done");
    assertThat(SdkTaskResult.ok("done", Map.of("k", "v")).output()).containsEntry("k", "v");
  }

  @Test
  @DisplayName("失败结果可由消息或异常构造,并保留异常引用")
  void shouldBuildFailureResult_fromMessageOrThrowable() {
    SdkTaskResult fromMsg = SdkTaskResult.fail("oops");
    assertThat(fromMsg.success()).isFalse();
    assertThat(fromMsg.message()).isEqualTo("oops");
    assertThat(fromMsg.error()).isNull();

    RuntimeException ex = new RuntimeException("boom");
    SdkTaskResult fromEx = SdkTaskResult.fail(ex);
    assertThat(fromEx.success()).isFalse();
    assertThat(fromEx.message()).isEqualTo("boom");
    assertThat(fromEx.error()).isSameAs(ex);
  }

  @Test
  @DisplayName("异常无消息时以异常类型名兜底")
  void shouldUseExceptionTypeName_whenMessageAbsent() {
    SdkTaskResult r = SdkTaskResult.fail(new IllegalStateException());
    assertThat(r.message()).isEqualTo("IllegalStateException");
  }

  @Test
  @DisplayName("失败结果工厂拒绝空消息与空异常")
  void shouldRejectNull_whenBuildingFailure() {
    assertThatThrownBy(() -> SdkTaskResult.fail((String) null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> SdkTaskResult.fail((Throwable) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("无输出时返回空视图而非空指针")
  void shouldReturnEmptyOutput_whenOutputAbsent() {
    SdkTaskResult r = new SdkTaskResult(true, "ok", null, null);
    assertThat(r.output()).isEmpty();
  }
}
