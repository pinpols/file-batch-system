package io.github.pinpols.batch.orchestrator.application.service.task;

import java.util.List;
import java.util.function.Consumer;

/**
 * R2:多行 INSERT 的 chunk 护栏。
 *
 * <p>PG 单条语句的绑定参数上限是 65535。多行 {@code insert ... values (...),(...),...} 的参数数 = 行数 × 每行列数; job_task
 * 19 列时 {@code 65535/19 ≈ 3449} 行即触上限,整批 INSERT 直接回滚。今天靠 {@code maxPartitionCount=256} (256×19≈4864
 * 参数)兜底不触发,但这是上游 cap 的巧合——一旦放开 256 上限或调大 batch,整批插入就会因参数越界回滚。
 *
 * <p>本工具把 chunk 上限内建到调用层:调用方按固定 {@link #DEFAULT_CHUNK_SIZE} 切批循环,每批独立走 {@code insertBatch}。
 *
 * <p><b>useGeneratedKeys 回填顺序</b>:{@link List#subList} 返回原 list 的<b>视图</b>,视图元素与原 list 是同一批
 * 对象引用;MyBatis 的 {@code useGeneratedKeys} 把生成的 id 按序回填到传入的(子)list 元素上,因此每个 chunk 的 回填直接落到原 list
 * 对应位置的对象,拼接回原顺序天然正确。
 *
 * <p>只切批、不改状态机语义:CAS/version 条件、每行取值表达式全部不动。
 */
final class BatchInsertChunks {

  /** 默认 chunk 大小。500 行 × 最宽表 19 列 = 9500 参数,距 65535 上限有 ~7 倍余量; 即便未来加列或放开 partition cap 也安全。 */
  static final int DEFAULT_CHUNK_SIZE = 500;

  private BatchInsertChunks() {}

  /**
   * 把 {@code items} 按 {@code chunkSize} 切成连续子 list,逐批喂给 {@code insert}。
   *
   * <p>空/单批直接一次调用;{@code insert} 接收的是原 list 的视图(见类 javadoc 的回填顺序保证)。
   */
  static <T> void insertInChunks(List<T> items, int chunkSize, Consumer<List<T>> insert) {
    if (items == null || items.isEmpty()) {
      return;
    }
    if (chunkSize <= 0) {
      throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
    }
    int size = items.size();
    for (int start = 0; start < size; start += chunkSize) {
      int end = Math.min(start + chunkSize, size);
      insert.accept(items.subList(start, end));
    }
  }
}
