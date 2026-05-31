package com.example.batch.sdk.handler;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.Iterator;

/**
 * ADR-036 — Export 模板:tenant → external(tenant DB → file)。
 *
 * <p>模板序:{@code openSink → buildQuery → streamRows → formatRow(逐行) → writeOut(收尾)}。子类只填 5 个钩子,
 * 计数与结果兜底由本基类统一处理。
 *
 * @param <R> 行类型
 */
public abstract class SdkAbstractExportHandler<R> extends SdkAbstractTaskHandler {

  /** 打开输出端(创建文件 / 开 S3 multipart / 开 writer)。 */
  protected abstract void openSink(SdkTaskContext ctx) throws Exception;

  /** 构造查询(从 ctx.parameters 拼 SQL / 过滤条件)。 */
  protected abstract String buildQuery(SdkTaskContext ctx) throws Exception;

  /** 按 query 流式读租户表行。 */
  protected abstract Iterator<R> streamRows(SdkTaskContext ctx, String query) throws Exception;

  /** 单行格式化写出(写一行到 sink)。 */
  protected abstract void formatRow(SdkTaskContext ctx, R row) throws Exception;

  /** 收尾(flush / close / upload),返回最终结果(可放文件 URI 等到 counts.toOutput 之外)。 */
  protected abstract SdkTaskResult writeOut(SdkTaskContext ctx, SdkRowResult counts)
      throws Exception;

  @Override
  protected final SdkTaskResult doExecute(SdkTaskContext ctx) {
    try {
      openSink(ctx);
      String q = buildQuery(ctx);
      SdkRowResult counts = new SdkRowResult();
      Iterator<R> it = streamRows(ctx, q);
      while (it.hasNext()) {
        formatRow(ctx, it.next());
        counts.incSuccess();
      }
      SdkTaskResult r = writeOut(ctx, counts);
      return r == null
          ? SdkTaskResult.ok("exported " + counts.success() + " rows", counts.toOutput())
          : r;
    } catch (Exception e) {
      return SdkTaskResult.fail(e);
    }
  }
}
