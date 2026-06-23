package io.github.pinpols.batch.sdk.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkTaskResultTest {

  @Test
  void okHelpers() {
    assertThat(SdkTaskResult.ok().success()).isTrue();
    assertThat(SdkTaskResult.ok().message()).isEqualTo("ok");
    assertThat(SdkTaskResult.ok("done").message()).isEqualTo("done");
    assertThat(SdkTaskResult.ok("done", Map.of("k", "v")).output()).containsEntry("k", "v");
  }

  @Test
  void failHelpers() {
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
  void failFromExWithoutMessage() {
    SdkTaskResult r = SdkTaskResult.fail(new IllegalStateException());
    assertThat(r.message()).isEqualTo("IllegalStateException");
  }

  @Test
  void failRequiresNonNull() {
    assertThatThrownBy(() -> SdkTaskResult.fail((String) null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> SdkTaskResult.fail((Throwable) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void outputDefensiveCopy() {
    SdkTaskResult r = new SdkTaskResult(true, "ok", null, null);
    assertThat(r.output()).isEmpty();
  }
}
