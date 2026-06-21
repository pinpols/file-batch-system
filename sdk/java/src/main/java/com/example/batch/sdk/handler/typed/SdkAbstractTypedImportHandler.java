package com.example.batch.sdk.handler.typed;

import com.example.batch.sdk.checkpoint.SdkCheckpointState;
import com.example.batch.sdk.handler.SdkAbstractTaskHandler;
import com.example.batch.sdk.handler.SdkRowResult;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import com.example.batch.sdk.task.SdkTaskStoppedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A.2 — typed Import 模板:把 A.1 的「强类型入参」与 ADR-036 的「行流/分批模板」合流。
 *
 * <p>租户既拿强类型入参 {@code I}(框架从 {@link SdkTaskContext#parameters()} 经 Jackson 反序列化), 又拿 Import
 * 阶段的行流模板(openSource → readRows(Stream) → 分批 loadBatch),无需再 {@code Map} 瞎转型。 模板负责
 * try-with-resources 关流 + 分批 flush + 计数回退。
 *
 * <p>设计取舍:复用 {@link SdkTypedParameters} 完成入参解析(组合,不破坏 {@code SdkTypedTaskHandler} 现有 API)。 入参
 * {@code I} 在执行起点解析一次并缓存,各钩子直接拿到它,避免每行重复反序列化。
 *
 * <p><b>ADR-037 续跑(P1~P3)</b>:execute 开头 {@code ctx.checkpoint().load(taskId)} 读回断点 —— {@code
 * completed} 直接幂等跳过, 否则把累计计数填回(进度不归零);每批 {@code loadBatch} 后由模板调 {@code ctx.commit(breakPosition)}
 * 三合一(业务写 + 断点保存 同事务 + 限流上报),提交后命中取消即在安全点抛 {@link SdkTaskStoppedException} → 模板顶层落 cancelled。要真续跑须
 * override {@link #breakPosition(I, List)} 返回本批最后一行的业务主键。
 *
 * <p><b>PG 服务端游标(流式读)</b>:{@link #readRows} 走 JDBC 时,PostgreSQL 默认<b>一次性拉全量结果集</b>,百万行会超过内存。真流式须在
 * 同一 connection 上 {@code connection.setAutoCommit(false)} + {@code statement.setFetchSize(N)}
 * 开服务端游标,逐批拉取。 注意此时 connection 处于事务中,与 {@code ctx.commit} 的同事务写要协调好边界(读连接与写连接通常分开)。
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
 * @param <O> 业务结果(序列化进 output;返 null 则用计数器 output 回退)
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
    // 无操作
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

  /**
   * ADR-037 决策一 — 计算本批<b>断点坐标</b>(已处理到的最后一条记录主键 / 排序键 / 行号)。默认返回空 Map(不续跑,仅有上报 / 取消语义)。
   *
   * <p>要真正断点续跑的租户应 override:返回 {@code batch} 最后一行的业务主键,如 {@code Map.of("id",
   * batch.get(batch.size()-1).id())}; 与 {@link #readRows} 的 {@code WHERE key > :breakPosition}
   * 同坐标系,使重派从断点往后续,而非从头重跑。
   */
  protected Map<String, Object> breakPosition(I input, List<R> batch) {
    return Map.of();
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
    // ADR-037 决策一:execute 开头读回断点。
    String taskKey = String.valueOf(ctx.taskId());
    Optional<SdkCheckpointState> resumed = ctx.checkpoint().load(taskKey);
    if (resumed.map(SdkCheckpointState::completed).orElse(false)) {
      return SdkTaskResult.ok("import already completed (resumed checkpoint), skipped");
    }
    SdkRowResult counts = new SdkRowResult();
    resumed.ifPresent(
        s -> {
          counts.addSuccess(s.succeedCount());
          ctx.commitCoordinator().restoreCounts(s.succeedCount(), s.failCount());
        });
    try {
      openSource(input, ctx);
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
      ctx.commitCoordinator().markCompleted(breakPosition(input, List.of()));
      return result(input, counts, "imported " + counts.success() + " rows");
    } catch (SdkTaskStoppedException stopped) {
      throw stopped; // 决策三:协作取消穿透到模板顶层落 cancelled,业务不得吞。
    } catch (Exception e) {
      return SdkTaskResult.fail(e);
    }
  }

  private void flush(I input, SdkTaskContext ctx, List<R> buf, SdkRowResult counts)
      throws Exception {
    loadBatch(input, ctx, buf);
    counts.addSuccess(buf.size());
    // ADR-037 决策二:业务写 + 断点保存 + 限流上报三合一(同事务);决策三:提交后命中取消则在安全点抛停止。
    ctx.commitCoordinator().recordBatch(buf.size(), 0);
    ctx.commit(breakPosition(input, buf));
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
