package com.example.batch.sdk.handler;

import com.example.batch.sdk.handler.typed.SdkAbstractTypedProcessHandler;
import com.example.batch.sdk.task.SdkTaskContext;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * ADR-036 — Process 模板:tenant → tenant(transform 写回)。
 *
 * <p>双泛型 {@code <I, O>}(输入行 / 输出行)。模板序:{@code selectInput → transform(逐行) → (按 batch 累积 O) → upsert
 * → 汇总}。{@code transform} 返回 {@code null} 表示该行 skip(不写)。
 *
 * <p>适用:租户表 → 业务计算(报表预聚合 / 维表 join / 业务规则推导)→ 写回租户表。 钩子里的所有状态由租户进程持有,平台只看终态 + counts(ADR-035 §6)。
 *
 * <p>本类是 {@link SdkAbstractTypedProcessHandler} 在「裸 Map 任务入参」下的特例:钩子只收 {@code ctx},模板循环复用 typed 基类。
 * 需要强类型任务入参时直接用 {@link SdkAbstractTypedProcessHandler}。
 *
 * @param <I> 输入行类型
 * @param <O> 输出行类型
 */
public abstract class SdkAbstractProcessHandler<I, O>
    extends SdkAbstractTypedProcessHandler<Map<String, Object>, I, O, Void> {

  /**
   * 读输入行(从租户表 select)。模板用 try-with-resources 关闭,保证背后的 {@code ResultSet} 在读完或异常时都释放; 租户可直接返 {@code
   * jdbcTemplate.queryForStream(...)}。
   */
  protected abstract Stream<I> selectInput(SdkTaskContext ctx) throws Exception;

  /** 单行转换 I→O;返回 null 表示该行 skip(不写)。 */
  protected abstract O transform(SdkTaskContext ctx, I input) throws Exception;

  /** 批量 upsert 输出行到租户表。 */
  protected abstract void upsert(SdkTaskContext ctx, List<O> batch) throws Exception;

  @Override
  protected final Stream<I> selectInput(Map<String, Object> params, SdkTaskContext ctx)
      throws Exception {
    return selectInput(ctx);
  }

  @Override
  protected final O transform(Map<String, Object> params, SdkTaskContext ctx, I row)
      throws Exception {
    return transform(ctx, row);
  }

  @Override
  protected final void upsert(Map<String, Object> params, SdkTaskContext ctx, List<O> batch)
      throws Exception {
    upsert(ctx, batch);
  }
}
