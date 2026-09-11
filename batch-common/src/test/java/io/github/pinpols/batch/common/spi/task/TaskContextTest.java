package io.github.pinpols.batch.common.spi.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskContextTest {

  @Test
  void preservesExplicitNullValuesInImmutableSnapshots() {
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
  void normalizesMissingMapsToEmptyImmutableMaps() {
    TaskContext context = new TaskContext("tenant", "job", null, null, null, null);

    assertThat(context.parameters()).isEmpty();
    assertThat(context.runtimeAttributes()).isEmpty();
    assertThatThrownBy(() -> context.parameters().put("key", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
