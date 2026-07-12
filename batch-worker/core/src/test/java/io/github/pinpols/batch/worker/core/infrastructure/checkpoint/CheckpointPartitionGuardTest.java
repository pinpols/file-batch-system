package io.github.pinpols.batch.worker.core.infrastructure.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("多分区降级守卫:缺失放行(单分区常态)/ present 非法 fail-closed 降级")
class CheckpointPartitionGuardTest {

  @Test
  @DisplayName("缺失(null)= 非分区任务 = 单分区 → 放行(不降级)")
  void nullMeansSinglePartition_allow() {
    assertThat(CheckpointPartitionGuard.shouldDegrade(null)).isFalse();
  }

  @Test
  @DisplayName("明确单分区(1)→ 放行")
  void one_allow() {
    assertThat(CheckpointPartitionGuard.shouldDegrade(1)).isFalse();
    assertThat(CheckpointPartitionGuard.shouldDegrade("1")).isFalse();
    assertThat(CheckpointPartitionGuard.shouldDegrade(1L)).isFalse();
  }

  @Test
  @DisplayName("多分区(>1)→ 降级")
  void multi_degrade() {
    assertThat(CheckpointPartitionGuard.shouldDegrade(2)).isTrue();
    assertThat(CheckpointPartitionGuard.shouldDegrade("16")).isTrue();
  }

  @Test
  @DisplayName("present 但非法(非数字 / <=0 / 空白)→ fail-closed 降级")
  void presentButIllegal_failClosed() {
    assertThat(CheckpointPartitionGuard.shouldDegrade("abc")).isTrue();
    assertThat(CheckpointPartitionGuard.shouldDegrade(0)).isTrue();
    assertThat(CheckpointPartitionGuard.shouldDegrade(-3)).isTrue();
    assertThat(CheckpointPartitionGuard.shouldDegrade("  ")).isTrue();
  }
}
