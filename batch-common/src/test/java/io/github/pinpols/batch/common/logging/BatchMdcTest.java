package io.github.pinpols.batch.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("日志上下文快照与还原:嵌套作用域后外层键保留,空快照整体清空")
class BatchMdcTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  @DisplayName("嵌套写入后还原快照:外层键保留,内层临时键被移除")
  void shouldRestoreOuterContextAfterNestedScope() {
    MDC.put("outer", "kept");
    Map<String, String> snapshot = BatchMdc.snapshot();

    MDC.put("inner", "temporary");
    BatchMdc.restore(snapshot);

    assertThat(MDC.get("outer")).isEqualTo("kept");
    assertThat(MDC.get("inner")).isNull();
  }

  @Test
  @DisplayName("快照为空时还原:上下文被整体清空,不残留临时键")
  void shouldClearContextWhenSnapshotWasEmpty() {
    Map<String, String> snapshot = BatchMdc.snapshot();
    MDC.put("inner", "temporary");

    BatchMdc.restore(snapshot);

    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }
}
