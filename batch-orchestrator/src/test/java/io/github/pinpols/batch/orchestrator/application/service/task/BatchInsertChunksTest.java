package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R2:chunk 护栏——分批 + useGeneratedKeys 回填顺序正确性。 */
@DisplayName("批量插入分片: 分片大小, 标识回填顺序与入参校验口径")
class BatchInsertChunksTest {

  /** 模拟 useGeneratedKeys 回填:给传入(子)list 每个元素按序赋 id。 */
  private static final class Row {
    Long id;
  }

  @Test
  @DisplayName("按分片大小向上切分并保持生成标识的回填顺序")
  void splitsIntoCeilChunks_andPreservesBackfillOrderAcrossChunks() {
    // 1200 行 / chunk 500 → 3 批(500,500,200)
    List<Row> rows = new ArrayList<>();
    for (int i = 0; i < 1200; i++) {
      rows.add(new Row());
    }
    List<Integer> chunkSizes = new ArrayList<>();
    AtomicInteger nextId = new AtomicInteger(1000);

    BatchInsertChunks.insertInChunks(rows, 500, chunk -> {
      chunkSizes.add(chunk.size());
      // useGeneratedKeys 语义:回填落到子 list 元素(= 原 list 同一对象引用)
      for (Row r : chunk) {
        r.id = (long) nextId.getAndIncrement();
      }
    });

    assertThat(chunkSizes).containsExactly(500, 500, 200);
    // 回填顺序:原 list 第 i 个元素拿到第 i 个生成 id(拼接回原顺序天然正确)
    for (int i = 0; i < rows.size(); i++) {
      assertThat(rows.get(i).id).as("row %d id", i).isEqualTo(1000L + i);
    }
  }

  @Test
  @DisplayName("行数恰好为分片整数倍时不产生尾部空批次")
  void exactMultipleOfChunkSize_noTrailingEmptyBatch() {
    List<Row> rows = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      rows.add(new Row());
    }
    List<Integer> chunkSizes = new ArrayList<>();

    BatchInsertChunks.insertInChunks(rows, 500, chunk -> chunkSizes.add(chunk.size()));

    assertThat(chunkSizes).containsExactly(500, 500);
  }

  @Test
  @DisplayName("行数小于分片大小时只执行一个批次")
  void smallerThanChunk_singleBatch() {
    List<Row> rows = List.of(new Row(), new Row());
    List<Integer> chunkSizes = new ArrayList<>();

    BatchInsertChunks.insertInChunks(rows, 500, chunk -> chunkSizes.add(chunk.size()));

    assertThat(chunkSizes).containsExactly(2);
  }

  @Test
  @DisplayName("入参为空或空列表时不触发任何批次")
  void emptyOrNull_noInvocation() {
    AtomicInteger calls = new AtomicInteger();
    BatchInsertChunks.insertInChunks(List.<Row>of(), 500, chunk -> calls.incrementAndGet());
    BatchInsertChunks.insertInChunks(null, 500, chunk -> calls.incrementAndGet());
    assertThat(calls.get()).isZero();
  }

  @Test
  @DisplayName("分片大小非正时抛出参数非法异常")
  void nonPositiveChunkSize_rejected() {
    assertThatThrownBy(() -> BatchInsertChunks.insertInChunks(List.of(new Row()), 0, chunk -> {}))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
