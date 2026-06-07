package com.example.batch.sdk.handler.typed;

import com.example.batch.sdk.handler.SdkAbstractTaskHandler;
import com.example.batch.sdk.handler.SdkRowResult;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.stream.Stream;

/**
 * A.2 — typed Export 模板:tenant → external(tenant DB → file),把强类型入参与 ADR-036 Export 行流模板合流。
 *
 * <p>模板序:{@code openSink → buildQuery → streamRows → formatRow(逐行) → writeOut(收尾)}。 租户拿强类型入参 {@code
 * I},无需 {@code Map} 转型。复用 {@link SdkTypedParameters} 解析入参(组合)。
 *
 * @param <I> 强类型入参(从 parameters 反序列化)
 * @param <O> 业务结果(序列化进 output;writeOut 自带结果优先,其次 summarize,最后计数器兜底)
 * @param <R> 行类型
 */
public abstract class SdkAbstractTypedExportHandler<I, O, R> extends SdkAbstractTaskHandler {

  private final SdkTypedParameters<I> params;

  protected SdkAbstractTypedExportHandler() {
    this(SdkTypedParameters.defaultObjectMapper());
  }

  protected SdkAbstractTypedExportHandler(ObjectMapper objectMapper) {
    this.params =
        SdkTypedParameters.forHandler(objectMapper, this, SdkAbstractTypedExportHandler.class, 0);
  }

  /** 打开输出端(创建文件 / 开 S3 multipart / 开 writer)。默认 no-op。 */
  protected void openSink(I input, SdkTaskContext ctx) throws Exception {
    // 无操作
  }

  /** 构造查询(从强类型入参拼 SQL / 过滤条件)。 */
  protected abstract String buildQuery(I input, SdkTaskContext ctx) throws Exception;

  /** 按 query 流式读租户表行。模板用 try-with-resources 关闭,保证背后的 {@code ResultSet} 在读完或异常时都释放。 */
  protected abstract Stream<R> streamRows(I input, SdkTaskContext ctx, String query)
      throws Exception;

  /** 单行格式化写出(写一行到 sink)。 */
  protected abstract void formatRow(I input, SdkTaskContext ctx, R row) throws Exception;

  /** 收尾(flush / close / upload);返 null 则走 summarize / 计数器兜底。 */
  protected SdkTaskResult writeOut(I input, SdkTaskContext ctx, SdkRowResult counts)
      throws Exception {
    return null;
  }

  /** 汇总成业务结果 {@code O};默认返 null。 */
  protected O summarize(I input, SdkRowResult counts) {
    return null;
  }

  @Override
  protected final SdkTaskResult doExecute(SdkTaskContext ctx) {
    I input;
    try {
      input = params.parse(ctx);
    } catch (IllegalArgumentException ex) {
      return SdkTaskResult.fail(
          "invalid parameters for taskType=" + taskType() + ": " + ex.getMessage(), ex);
    }
    try {
      openSink(input, ctx);
      String q = buildQuery(input, ctx);
      SdkRowResult counts = new SdkRowResult();
      try (Stream<R> rows = streamRows(input, ctx, q)) {
        Iterator<R> it = rows.iterator();
        while (it.hasNext()) {
          formatRow(input, ctx, it.next());
          counts.incSuccess();
        }
      }
      SdkTaskResult explicit = writeOut(input, ctx, counts);
      if (explicit != null) {
        return explicit;
      }
      return result(input, counts, "exported " + counts.success() + " rows");
    } catch (Exception e) {
      return SdkTaskResult.fail(e);
    }
  }

  private SdkTaskResult result(I input, SdkRowResult counts, String defaultMessage) {
    O output = summarize(input, counts);
    if (output == null) {
      return SdkTaskResult.ok(defaultMessage, counts.toOutput());
    }
    return SdkTaskResult.ok(defaultMessage, params.toOutputMap(output));
  }
}
