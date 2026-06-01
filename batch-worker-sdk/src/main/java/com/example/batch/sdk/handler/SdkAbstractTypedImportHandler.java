package com.example.batch.sdk.handler;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A.2 — typed Import 模板:把 A.1 的「强类型入参」与 ADR-036 的「行流/分批模板」合流。
 *
 * <p>租户既拿强类型入参 {@code I}(框架从 {@link SdkTaskContext#parameters()} 经 Jackson 反序列化), 又拿 Import
 * 阶段的行流模板(openSource → readRows(Stream) → 分批 loadBatch),无需再 {@code Map} 瞎转型。 模板负责
 * try-with-resources 关流 + 分批 flush + 计数兜底。
 *
 * <p>设计取舍:复用 {@link SdkTypedParameters} 完成入参解析(组合,不破坏 {@code SdkTypedTaskHandler} 现有 API)。 入参
 * {@code I} 在执行起点解析一次并缓存,各钩子直接拿到它,避免每行重复反序列化。
 *
 * <p>典型实现:
 *
 * <pre>{@code
 * public class CsvImportHandler
 *     extends SdkAbstractTypedImportHandler<ImportRequest, ImportResult, CsvRow> {
 *   @Override public String taskType() { return "tenant_csv_import"; }
 *   @Override protected Stream<CsvRow> readRows(ImportRequest req, SdkTaskContext ctx) {
 *     return Files.lines(Path.of(req.sourcePath())).map(CsvRow::parse);
 *   }
 *   @Override protected void loadBatch(ImportRequest req, SdkTaskContext ctx, List<CsvRow> b) {
 *     jdbc.batchInsert(b);
 *   }
 *   @Override protected ImportResult summarize(ImportRequest req, SdkRowResult c) {
 *     return new ImportResult(c.success());
 *   }
 * }
 * }</pre>
 *
 * @param <I> 强类型入参(从 parameters 反序列化)
 * @param <O> 业务结果(序列化进 output;返 null 则用计数器 output 兜底)
 * @param <R> 行类型
 */
public abstract class SdkAbstractTypedImportHandler<I, O, R> extends SdkAbstractTaskHandler {

  private final SdkTypedParameters<I> params;

  protected SdkAbstractTypedImportHandler() {
    this(SdkTypedParameters.defaultObjectMapper());
  }

  protected SdkAbstractTypedImportHandler(ObjectMapper objectMapper) {
    this.params =
        SdkTypedParameters.forHandler(objectMapper, this, SdkAbstractTypedImportHandler.class, 0);
  }

  /** 打开数据源(连 SFTP / 下载文件 / 开 stream)。默认 no-op。 */
  protected void openSource(I input, SdkTaskContext ctx) throws Exception {
    // no-op
  }

  /**
   * 返回行流(逐行解析)。模板用 try-with-resources 关闭,保证背后的 {@code ResultSet} / {@code InputStream} 在读完或异常时都释放。
   */
  protected abstract Stream<R> readRows(I input, SdkTaskContext ctx) throws Exception;

  /** 批量写入租户自家目标表。 */
  protected abstract void loadBatch(I input, SdkTaskContext ctx, List<R> batch) throws Exception;

  /** 批大小,默认 1000,可覆盖。 */
  protected int batchSize() {
    return 1000;
  }

  /** 汇总成业务结果 {@code O};默认返 null(则走计数器 output)。 */
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
      openSource(input, ctx);
      SdkRowResult counts = new SdkRowResult();
      List<R> buf = new ArrayList<>(batchSize());
      try (Stream<R> rows = readRows(input, ctx)) {
        Iterator<R> it = rows.iterator();
        while (it.hasNext()) {
          buf.add(it.next());
          if (buf.size() >= batchSize()) {
            flush(input, ctx, buf, counts);
          }
        }
      }
      if (!buf.isEmpty()) {
        flush(input, ctx, buf, counts);
      }
      return result(input, counts, "imported " + counts.success() + " rows");
    } catch (Exception e) {
      return SdkTaskResult.fail(e);
    }
  }

  private void flush(I input, SdkTaskContext ctx, List<R> buf, SdkRowResult counts)
      throws Exception {
    loadBatch(input, ctx, buf);
    counts.addSuccess(buf.size());
    buf.clear();
  }

  private SdkTaskResult result(I input, SdkRowResult counts, String defaultMessage) {
    O output = summarize(input, counts);
    if (output == null) {
      return SdkTaskResult.ok(defaultMessage, counts.toOutput());
    }
    return SdkTaskResult.ok(defaultMessage, params.toOutputMap(output));
  }
}
