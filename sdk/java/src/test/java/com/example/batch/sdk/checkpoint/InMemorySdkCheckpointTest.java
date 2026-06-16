package com.example.batch.sdk.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-037 决策一 — 断点存储 load/save 协议单测。 */
class InMemorySdkCheckpointTest {

  @Test
  @DisplayName("首次 load 返回 empty;save 后 load 读回同一状态")
  void shouldLoadEmptyThenReadBackAfterSave() {
    // arrange
    InMemorySdkCheckpoint cp = new InMemorySdkCheckpoint();

    // act + assert: 首次为空
    assertThat(cp.load("task-1")).isEmpty();

    // act: save
    SdkCheckpointState state = new SdkCheckpointState(Map.of("id", 42), 100L, 3L, false);
    cp.save("task-1", state);

    // assert: 读回一致
    assertThat(cp.load("task-1")).contains(state);
    assertThat(cp.load("task-1"))
        .get()
        .satisfies(
            s -> {
              assertThat(s.breakPosition()).containsEntry("id", 42);
              assertThat(s.succeedCount()).isEqualTo(100L);
              assertThat(s.failCount()).isEqualTo(3L);
              assertThat(s.completed()).isFalse();
            });
  }

  @Test
  @DisplayName("同 taskId 重复 save 覆盖;不同 taskId 互不影响")
  void shouldOverwriteSameTaskAndIsolateAcrossTasks() {
    // arrange
    InMemorySdkCheckpoint cp = new InMemorySdkCheckpoint();
    cp.save("a", new SdkCheckpointState(Map.of("k", 1), 1L, 0L, false));

    // act: 覆盖 a,新增 b
    cp.save("a", new SdkCheckpointState(Map.of("k", 2), 2L, 0L, true));
    cp.save("b", new SdkCheckpointState(Map.of("k", 9), 9L, 0L, false));

    // assert
    assertThat(cp.load("a"))
        .get()
        .satisfies(
            s -> {
              assertThat(s.succeedCount()).isEqualTo(2L);
              assertThat(s.completed()).isTrue();
            });
    assertThat(cp.load("b")).get().satisfies(s -> assertThat(s.succeedCount()).isEqualTo(9L));
    assertThat(cp.size()).isEqualTo(2);
  }

  @Test
  @DisplayName("breakPosition 为不可变拷贝,外部改原 Map 不影响已存状态")
  void shouldCopyBreakPositionDefensively() {
    // arrange
    InMemorySdkCheckpoint cp = new InMemorySdkCheckpoint();
    Map<String, Object> mutable = new HashMap<>();
    mutable.put("id", 1);

    // act
    cp.save("t", new SdkCheckpointState(mutable, 1L, 0L, false));
    mutable.put("id", 999);

    // assert
    assertThat(cp.load("t"))
        .get()
        .satisfies(s -> assertThat(s.breakPosition()).containsEntry("id", 1));
  }
}
