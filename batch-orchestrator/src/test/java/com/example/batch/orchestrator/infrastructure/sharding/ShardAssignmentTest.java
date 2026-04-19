package com.example.batch.orchestrator.infrastructure.sharding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ShardAssignmentTest {

  @Test
  void single_returns_total_1_index_0() {
    ShardAssignment assignment = ShardAssignment.single();
    assertThat(assignment.shardTotal()).isEqualTo(1);
    assertThat(assignment.shardIndex()).isEqualTo(0);
  }

  @Test
  void rejects_zero_or_negative_total() {
    assertThatThrownBy(() -> new ShardAssignment(0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shardTotal");
    assertThatThrownBy(() -> new ShardAssignment(-1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_index_out_of_range() {
    assertThatThrownBy(() -> new ShardAssignment(3, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shardIndex");
    assertThatThrownBy(() -> new ShardAssignment(3, 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ShardAssignment(3, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void accepts_valid_assignment() {
    ShardAssignment a = new ShardAssignment(4, 2);
    assertThat(a.shardTotal()).isEqualTo(4);
    assertThat(a.shardIndex()).isEqualTo(2);
  }
}
