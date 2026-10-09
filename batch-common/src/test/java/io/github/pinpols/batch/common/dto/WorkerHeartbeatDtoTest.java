package io.github.pinpols.batch.common.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Worker 心跳 DTO 执行器能力快照边界")
class WorkerHeartbeatDtoTest {

  private static final WorkerTaskCapabilityDto CAPABILITY =
      new WorkerTaskCapabilityDto("checksum", List.of("DISK"), true, false, 1);

  @Test
  @DisplayName("拒绝超过上限的能力条目")
  void shouldRejectTooManyTaskCapabilities() {
    List<WorkerTaskCapabilityDto> capabilities = Collections.nCopies(129, CAPABILITY);

    assertThatThrownBy(
            () -> WorkerHeartbeatDto.builder().taskCapabilities(capabilities).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most 128");
  }

  @Test
  @DisplayName("防御性复制能力列表")
  void shouldCopyTaskCapabilities() {
    List<WorkerTaskCapabilityDto> capabilities = new ArrayList<>(List.of(CAPABILITY));

    WorkerHeartbeatDto heartbeat =
        WorkerHeartbeatDto.builder().taskCapabilities(capabilities).build();
    capabilities.clear();

    assertThat(heartbeat.taskCapabilities()).containsExactly(CAPABILITY);
  }
}
