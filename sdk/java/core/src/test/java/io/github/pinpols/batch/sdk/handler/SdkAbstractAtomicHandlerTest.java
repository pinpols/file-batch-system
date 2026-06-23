package io.github.pinpols.batch.sdk.handler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SdkAbstractAtomicHandler — ADR-036 Atomic 业务模板")
class SdkAbstractAtomicHandlerTest {

  private static SdkTaskContext ctx() {
    return new SdkTaskContext("tx", "job", "ti", 1L, "w-1", Map.of(), Map.of());
  }

  @Test
  @DisplayName("doInvoke 返回值 → execute success + output {result: ...}")
  void shouldWrapReturnValueAsOutput_whenInvokeReturnsValue() {
    // 准备
    var handler =
        new SdkAbstractAtomicHandler<String>() {
          @Override
          public String taskType() {
            return "tt";
          }

          @Override
          protected String doInvoke(SdkTaskContext c) {
            return "hello";
          }
        };

    // 执行
    SdkTaskResult result = handler.execute(ctx());

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(result.message()).isEqualTo("invoked");
    assertThat(result.output()).containsEntry("result", "hello");
    assertThat(result.error()).isNull();
  }

  @Test
  @DisplayName("doInvoke 返回 null → output 空 Map 但 success=true")
  void shouldReturnEmptyOutput_whenInvokeReturnsNull() {
    // 准备
    var handler =
        new SdkAbstractAtomicHandler<String>() {
          @Override
          public String taskType() {
            return "tt";
          }

          @Override
          protected String doInvoke(SdkTaskContext c) {
            return null;
          }
        };

    // 执行
    SdkTaskResult result = handler.execute(ctx());

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(result.output()).isEmpty();
  }

  @Test
  @DisplayName("doInvoke 抛异常 → execute fail + error 透传")
  void shouldFailAndPropagateError_whenInvokeThrows() {
    // 准备
    var boom = new IllegalStateException("boom");
    var handler =
        new SdkAbstractAtomicHandler<String>() {
          @Override
          public String taskType() {
            return "tt";
          }

          @Override
          protected String doInvoke(SdkTaskContext c) {
            throw boom;
          }
        };

    // 执行
    SdkTaskResult result = handler.execute(ctx());

    // 断言
    assertThat(result.success()).isFalse();
    assertThat(result.error()).isSameAs(boom);
    assertThat(result.message()).isEqualTo("boom");
  }

  @Test
  @DisplayName("子类覆盖 asOutput → output 用子类映射")
  void shouldUseCustomOutput_whenAsOutputOverridden() {
    // 准备
    var handler =
        new SdkAbstractAtomicHandler<Integer>() {
          @Override
          public String taskType() {
            return "tt";
          }

          @Override
          protected Integer doInvoke(SdkTaskContext c) {
            return 42;
          }

          @Override
          protected Map<String, Object> asOutput(Integer r) {
            return Map.of("count", r, "kind", "custom");
          }
        };

    // 执行
    SdkTaskResult result = handler.execute(ctx());

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("count", 42).containsEntry("kind", "custom");
    assertThat(result.output()).doesNotContainKey("result");
  }

  @Test
  @DisplayName("validate 抛异常 → execute fail 且 doInvoke 不被调")
  void shouldFailWithoutInvoking_whenValidateThrows() {
    // 准备
    var invoked = new AtomicBoolean(false);
    var validationError = new IllegalArgumentException("bad param");
    var handler =
        new SdkAbstractAtomicHandler<String>() {
          @Override
          public String taskType() {
            return "tt";
          }

          @Override
          protected void validate(SdkTaskContext c) {
            throw validationError;
          }

          @Override
          protected String doInvoke(SdkTaskContext c) {
            invoked.set(true);
            return "should-not-run";
          }
        };

    // 执行
    SdkTaskResult result = handler.execute(ctx());

    // 断言
    assertThat(result.success()).isFalse();
    assertThat(result.error()).isSameAs(validationError);
    assertThat(invoked).isFalse();
  }

  @Test
  @DisplayName("cleanup 必跑:doInvoke 抛异常也执行覆盖的 cleanup")
  void shouldRunCleanup_whenInvokeThrows() {
    // 准备
    var cleaned = new AtomicBoolean(false);
    var handler =
        new SdkAbstractAtomicHandler<String>() {
          @Override
          public String taskType() {
            return "tt";
          }

          @Override
          protected String doInvoke(SdkTaskContext c) {
            throw new IllegalStateException("boom");
          }

          @Override
          protected void cleanup(SdkTaskContext c) {
            cleaned.set(true);
          }
        };

    // 执行
    SdkTaskResult result = handler.execute(ctx());

    // 断言
    assertThat(result.success()).isFalse();
    assertThat(cleaned).isTrue();
  }

  @Test
  @DisplayName("cleanup 在成功路径也执行")
  void shouldRunCleanup_whenInvokeSucceeds() {
    // 准备
    var cleaned = new AtomicBoolean(false);
    var handler =
        new SdkAbstractAtomicHandler<String>() {
          @Override
          public String taskType() {
            return "tt";
          }

          @Override
          protected String doInvoke(SdkTaskContext c) {
            return "ok";
          }

          @Override
          protected void cleanup(SdkTaskContext c) {
            cleaned.set(true);
          }
        };

    // 执行
    SdkTaskResult result = handler.execute(ctx());

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(cleaned).isTrue();
  }

  @Test
  @DisplayName("taskType 由具体子类提供")
  void shouldExposeTaskType_fromConcreteSubclass() {
    // 准备
    var handler =
        new SdkAbstractAtomicHandler<String>() {
          @Override
          public String taskType() {
            return "tenant_atomic_shell";
          }

          @Override
          protected String doInvoke(SdkTaskContext c) {
            return "x";
          }
        };

    // 断言
    assertThat(handler.taskType()).isEqualTo("tenant_atomic_shell");
  }
}
