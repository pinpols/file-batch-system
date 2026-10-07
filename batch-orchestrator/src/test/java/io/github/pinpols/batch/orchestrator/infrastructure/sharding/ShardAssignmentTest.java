package io.github.pinpols.batch.orchestrator.infrastructure.sharding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("分片赋值:分片总数与分片下标的合法区间校验,以及单分片场景的默认取值")
class ShardAssignmentTest {

  @Test
  @DisplayName("单分片场景下分片总数为 1 且下标为 0")
  void shouldReturnTotalOneAndIndexZero_whenSingleShard() {
    ShardAssignment assignment = ShardAssignment.single();
    assertThat(assignment.shardTotal()).isEqualTo(1);
    assertThat(assignment.shardIndex()).isZero();
  }

  @Test
  @DisplayName("分片总数为 0 或负数时拒绝创建并提示分片总数非法")
  void shouldReject_whenShardTotalNotPositive() {
    assertThatThrownBy(() -> new ShardAssignment(0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shardTotal");
    assertThatThrownBy(() -> new ShardAssignment(-1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("分片下标为负数或不小于分片总数时拒绝创建并提示下标非法")
  void shouldReject_whenShardIndexOutOfRange() {
    assertThatThrownBy(() -> new ShardAssignment(3, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shardIndex");
    assertThatThrownBy(() -> new ShardAssignment(3, 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ShardAssignment(3, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("分片下标位于有效区间内时创建成功并原样保留给定取值")
  void shouldAccept_whenShardIndexInsideRange() {
    ShardAssignment a = new ShardAssignment(4, 2);
    assertThat(a.shardTotal()).isEqualTo(4);
    assertThat(a.shardIndex()).isEqualTo(2);
  }
}
