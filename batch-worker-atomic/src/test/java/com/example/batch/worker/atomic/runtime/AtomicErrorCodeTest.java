package com.example.batch.worker.atomic.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.spi.task.TaskResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link AtomicErrorCode} 单测:覆盖三个 fail() 重载的语义。 */
class AtomicErrorCodeTest {

  @Test
  void fail_message_shouldPopulateOutputErrorCode() {
    TaskResult r = AtomicErrorCode.fail(AtomicErrorCode.TIMEOUT, "deadline exceeded");

    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("deadline exceeded");
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "TIMEOUT");
    assertThat(r.error()).isNull();
  }

  @Test
  void fail_messageAndCause_shouldCarryThrowable() {
    RuntimeException cause = new RuntimeException("boom");
    TaskResult r = AtomicErrorCode.fail(AtomicErrorCode.EXECUTION_FAILED, "stmt failed", cause);

    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "EXECUTION_FAILED");
    assertThat(r.error()).isSameAs(cause);
  }

  @Test
  void fail_withExtraOutput_shouldMergeAndOverrideErrorCode() {
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("exitCode", 137);
    extra.put(AtomicErrorCode.OUTPUT_KEY, "WILL_BE_OVERRIDDEN");

    TaskResult r = AtomicErrorCode.fail(AtomicErrorCode.KILLED, "sigkill", extra, null);

    assertThat(r.output()).containsEntry("exitCode", 137);
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "KILLED");
  }

  @Test
  void fail_withNullExtraOutput_shouldStillSetErrorCode() {
    TaskResult r =
        AtomicErrorCode.fail(AtomicErrorCode.RESOURCE_EXHAUSTED, "truncated", null, null);

    assertThat(r.output()).containsOnlyKeys(AtomicErrorCode.OUTPUT_KEY);
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "RESOURCE_EXHAUSTED");
  }

  @Test
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
