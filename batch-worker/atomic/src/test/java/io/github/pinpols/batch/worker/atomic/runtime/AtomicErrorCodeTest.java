package io.github.pinpols.batch.worker.atomic.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.spi.task.TaskResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link AtomicErrorCode} 单测:覆盖三个 fail() 重载的语义。 */
@DisplayName("原子错误码: 失败结果构造与枚举名稳定性")
class AtomicErrorCodeTest {

  @Test
  @DisplayName("按错误码与消息构造失败结果时, 输出应带上错误码且不附带异常对象")
  void fail_message_shouldPopulateOutputErrorCode() {
    TaskResult r = AtomicErrorCode.fail(AtomicErrorCode.TIMEOUT, "deadline exceeded");

    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("deadline exceeded");
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "TIMEOUT");
    assertThat(r.error()).isNull();
  }

  @Test
  @DisplayName("带异常构造失败结果时, 应保留原始异常引用以便归因")
  void fail_messageAndCause_shouldCarryThrowable() {
    RuntimeException cause = new RuntimeException("boom");
    TaskResult r = AtomicErrorCode.fail(AtomicErrorCode.EXECUTION_FAILED, "stmt failed", cause);

    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "EXECUTION_FAILED");
    assertThat(r.error()).isSameAs(cause);
  }

  @Test
  @DisplayName("附加输出应合并进结果, 并以传入错误码覆盖其中同名键")
  void fail_withExtraOutput_shouldMergeAndOverrideErrorCode() {
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("exitCode", 137);
    extra.put(AtomicErrorCode.OUTPUT_KEY, "WILL_BE_OVERRIDDEN");

    TaskResult r = AtomicErrorCode.fail(AtomicErrorCode.KILLED, "sigkill", extra, null);

    assertThat(r.output()).containsEntry("exitCode", 137);
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "KILLED");
  }

  @Test
  @DisplayName("附加输出为空时也应只写入错误码键, 不产生多余字段")
  void fail_withNullExtraOutput_shouldStillSetErrorCode() {
    TaskResult r =
        AtomicErrorCode.fail(AtomicErrorCode.RESOURCE_EXHAUSTED, "truncated", null, null);

    assertThat(r.output()).containsOnlyKeys(AtomicErrorCode.OUTPUT_KEY);
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "RESOURCE_EXHAUSTED");
  }

  @Test
  @DisplayName("枚举字面名是下游归因依据, 六个错误码取值应保持稳定")
  void enumNames_shouldBeStableForDownstreamConsumers() {
    // 下游基于字面 enum 名做归因 — 改名 = breaking change,守护这点
    assertThat(AtomicErrorCode.TIMEOUT.name()).isEqualTo("TIMEOUT");
    assertThat(AtomicErrorCode.KILLED.name()).isEqualTo("KILLED");
    assertThat(AtomicErrorCode.SECURITY_REJECTED.name()).isEqualTo("SECURITY_REJECTED");
    assertThat(AtomicErrorCode.EXECUTION_FAILED.name()).isEqualTo("EXECUTION_FAILED");
    assertThat(AtomicErrorCode.CONFIG_INVALID.name()).isEqualTo("CONFIG_INVALID");
    assertThat(AtomicErrorCode.RESOURCE_EXHAUSTED.name()).isEqualTo("RESOURCE_EXHAUSTED");
  }
}
