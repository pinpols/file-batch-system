package com.example.batch.sdk.handler;

import com.example.batch.sdk.handler.typed.SdkAbstractTypedExportHandler;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.Map;
import java.util.stream.Stream;

/**
 * ADR-036 — Export 模板:tenant → external(tenant DB → file)。
 *
 * <p>模板序:{@code openSink → buildQuery → streamRows → formatRow(逐行) → writeOut(收尾)}。子类只填 5 个钩子,
 * 计数与结果回退由模板统一处理。
 *
 * <p>本类是 {@link SdkAbstractTypedExportHandler} 在「裸 Map 入参」下的特例:钩子只收 {@code ctx},模板循环复用 typed 基类。
 * 需要强类型入参时直接用 {@link SdkAbstractTypedExportHandler}。
 *
 * @param <R> 行类型
 */
public abstract class SdkAbstractExportHandler<R>
    extends SdkAbstractTypedExportHandler<Map<String, Object>, Void, R> {

  /** 打开输出端(创建文件 / 开 S3 multipart / 开 writer)。 */
  protected abstract void openSink(SdkTaskContext ctx) throws Exception;

  /** 构造查询(从 ctx.parameters 拼 SQL / 过滤条件)。 */
  protected abstract String buildQuery(SdkTaskContext ctx) throws Exception;

  /**
   * 按 query 流式读租户表行。模板用 try-with-resources 关闭,保证背后的 {@code ResultSet} 在读完或异常时都释放; 租户可直接返 {@code
   * jdbcTemplate.queryForStream(query, rowMapper)}。
   */
  protected abstract Stream<R> streamRows(SdkTaskContext ctx, String query) throws Exception;

  /** 单行格式化写出(写一行到 sink)。 */
  protected abstract void formatRow(SdkTaskContext ctx, R row) throws Exception;

  /** 收尾(flush / close / upload),返回最终结果(可放文件 URI 等到 counts.toOutput 之外);返 null 走计数器回退。 */
  protected abstract SdkTaskResult writeOut(SdkTaskContext ctx, SdkRowResult counts)
      throws Exception;

  @Override
  protected final void openSink(Map<String, Object> input, SdkTaskContext ctx) throws Exception {
    openSink(ctx);
  }

  @Override
  protected final String buildQuery(Map<String, Object> input, SdkTaskContext ctx)
      throws Exception {
    return buildQuery(ctx);
  }

  @Override
  protected final Stream<R> streamRows(Map<String, Object> input, SdkTaskContext ctx, String query)
      throws Exception {
    return streamRows(ctx, query);
  }

  @Override
  protected final void formatRow(Map<String, Object> input, SdkTaskContext ctx, R row)
      throws Exception {
    formatRow(ctx, row);
  }

  @Override
  protected final SdkTaskResult writeOut(
      Map<String, Object> input, SdkTaskContext ctx, SdkRowResult counts) throws Exception {
    return writeOut(ctx, counts);
  }
}
