package io.github.pinpols.batch.common.spi.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("任务上下文:参数与运行属性的不可变快照及缺失映射归一")
class TaskContextTest {

  @Test
  @DisplayName("快照语义:显式空值保留, 事后改动不入快照, 且取值不可修改")
  void shouldPreserveExplicitNullValues_whenSnapshotTaken() {
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("optionalParameter", null);
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("taskInstanceId", null);
    attributes.put("dryRun", true);

    TaskContext context = new TaskContext("tenant", "job", null, "worker", parameters, attributes);
    parameters.put("lateMutation", "ignored");
    attributes.put("dryRun", false);

    assertThat(context.parameters()).containsEntry("optionalParameter", null);
    assertThat(context.parameters()).doesNotContainKey("lateMutation");
    assertThat(context.runtimeAttributes()).containsEntry("taskInstanceId", null);
    assertThat(context.isDryRun()).isTrue();
    assertThatThrownBy(() -> context.runtimeAttributes().put("newKey", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("参数与属性均为空:归一为空集合, 且写入被拒绝")
  void shouldNormalizeToEmptyImmutableMaps_whenMapsMissing() {
    TaskContext context = new TaskContext("tenant", "job", null, null, null, null);

    assertThat(context.parameters()).isEmpty();
    assertThat(context.runtimeAttributes()).isEmpty();
    assertThatThrownBy(() -> context.parameters().put("key", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
