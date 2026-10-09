package io.github.pinpols.batch.common.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Worker 执行器能力摘要 DTO 校验")
class WorkerTaskCapabilityDtoTest {

  @Test
  @DisplayName("拒绝空白任务类型")
  void shouldRejectBlankTaskType() {
    assertThatThrownBy(() -> new WorkerTaskCapabilityDto(" ", List.of("DISK"), true, false, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("taskType");
  }

  @Test
  @DisplayName("拒绝空资源类型集合")
  void shouldRejectEmptyResourceKinds() {
    assertThatThrownBy(() -> new WorkerTaskCapabilityDto("checksum", List.of(), true, false, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resourceKinds");
  }

  @Test
  @DisplayName("拒绝空白资源类型")
  void shouldRejectBlankResourceKind() {
    assertThatThrownBy(() -> new WorkerTaskCapabilityDto("checksum", List.of(" "), true, false, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank values");
  }

  @Test
  @DisplayName("拒绝非正推荐超时")
  void shouldRejectNonPositiveRecommendedTimeout() {
    assertThatThrownBy(
            () -> new WorkerTaskCapabilityDto("checksum", List.of("DISK"), true, false, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recommendedTimeoutMillis");
  }

  @Test
  @DisplayName("复制资源类型集合避免调用方修改能力快照")
  void shouldCopyResourceKinds() {
    List<String> resourceKinds = new ArrayList<>(List.of("DISK"));

    WorkerTaskCapabilityDto capability =
        new WorkerTaskCapabilityDto("checksum", resourceKinds, true, false, 1);
    resourceKinds.add("CPU");

    assertThat(capability.resourceKinds()).containsExactly("DISK");
  }
}
