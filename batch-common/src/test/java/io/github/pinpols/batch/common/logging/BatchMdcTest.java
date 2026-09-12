package io.github.pinpols.batch.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class BatchMdcTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void shouldRestoreOuterContextAfterNestedScope() {
    MDC.put("outer", "kept");
    Map<String, String> snapshot = BatchMdc.snapshot();

    MDC.put("inner", "temporary");
    BatchMdc.restore(snapshot);

    assertThat(MDC.get("outer")).isEqualTo("kept");
    assertThat(MDC.get("inner")).isNull();
  }

  @Test
  void shouldClearContextWhenSnapshotWasEmpty() {
    Map<String, String> snapshot = BatchMdc.snapshot();
    MDC.put("inner", "temporary");

    BatchMdc.restore(snapshot);

    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }
}
