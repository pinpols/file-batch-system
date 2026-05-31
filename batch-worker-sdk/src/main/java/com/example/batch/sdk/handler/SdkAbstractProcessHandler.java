package com.example.batch.sdk.handler;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * ADR-036 — Process 模板:tenant → tenant(transform 写回)。
 *
 * <p>双泛型 {@code <I, O>}(输入行 / 输出行)。模板序:{@code selectInput → transform(逐行) → (按 batch 累积 O) → upsert
 * → 汇总}。{@code transform} 返回 {@code null} 表示该行 skip(不写)。
 *
 * <p>适用:租户表 → 业务计算(报表预聚合 / 维表 join / 业务规则推导)→ 写回租户表。 钩子里的所有状态由租户进程持有,平台只看终态 + counts(ADR-035 §6)。
 *
 * @param <I> 输入行类型
 * @param <O> 输出行类型
 */
public abstract class SdkAbstractProcessHandler<I, O> extends SdkAbstractTaskHandler {

  /**
   * 读输入行(从租户表 select)。模板用 try-with-resources 关闭,保证背后的 {@code ResultSet} 在读完或异常时都释放; 租户可直接返 {@code
   * jdbcTemplate.queryForStream(...)}。
   */
  protected abstract Stream<I> selectInput(SdkTaskContext ctx) throws Exception;

  /** 单行转换 I→O;返回 null 表示该行 skip(不写)。 */
  protected abstract O transform(SdkTaskContext ctx, I input) throws Exception;

  /** 批量 upsert 输出行到租户表。 */
  protected abstract void upsert(SdkTaskContext ctx, List<O> batch) throws Exception;

  /** 批大小,默认 500,可覆盖。 */
  protected int batchSize() {
    return 500;
  }

  @Override
  protected final SdkTaskResult doExecute(SdkTaskContext ctx) {
    try {
      SdkRowResult counts = new SdkRowResult();
      List<O> buf = new ArrayList<>(batchSize());
      try (Stream<I> rows = selectInput(ctx)) {
        Iterator<I> it = rows.iterator();
        while (it.hasNext()) {
          O out = transform(ctx, it.next());
          if (out != null) {
            buf.add(out);
            counts.incSuccess();
          } else {
            counts.incSkipped();
          }
          if (buf.size() >= batchSize()) {
            upsert(ctx, buf);
            buf.clear();
          }
        }
      }
      if (!buf.isEmpty()) {
        upsert(ctx, buf);
      }
      return SdkTaskResult.ok("processed " + counts.success() + " rows", counts.toOutput());
    } catch (Exception e) {
      return SdkTaskResult.fail(e);
    }
  }
}
