package com.example.batch.sdk.handler.typed;

import com.example.batch.sdk.checkpoint.SdkCheckpointState;
import com.example.batch.sdk.handler.SdkAbstractTaskHandler;
import com.example.batch.sdk.handler.SdkRowResult;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import com.example.batch.sdk.task.SdkTaskStoppedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A.2 — typed Export 模板:tenant → external(tenant DB → file),把强类型入参与 ADR-036 Export 行流模板合流。
 *
 * <p>模板序:{@code openSink → buildQuery → streamRows → formatRow(逐行) → writeOut(收尾)}。 租户拿强类型入参 {@code
 * I},无需 {@code Map} 转型。复用 {@link SdkTypedParameters} 解析入参(组合)。
 *
 * <p><b>ADR-037 续跑(P1~P3)</b>:execute 开头 {@code ctx.checkpoint().load(taskId)} 读回断点(completed 幂等跳过
 * / 否则恢复计数); 逐行写出,每 {@link #commitIntervalRows()} 行 {@code ctx.commit(breakPosition)} 三合一(断点保存同事务 +
 * 限流上报),提交后命中取消即 在安全点抛 {@link SdkTaskStoppedException} → 模板顶层落 cancelled。要真续跑须 override {@link
 * #breakPosition(I, R)}。
 *
 * <p><b>PG 服务端游标(流式读)</b>:{@link #streamRows} 走 JDBC 时须 {@code setAutoCommit(false)} + {@code
 * setFetchSize(N)} 才是真流式,否则 PostgreSQL 默认一次性拉全量结果集超过内存。
 *
 * @param <I> 强类型入参(从 parameters 反序列化)
 * @param <O> 业务结果(序列化进 output;writeOut 自带结果优先,其次 summarize,最后计数器回退)
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

  /** 收尾(flush / close / upload);返 null 则走 summarize / 计数器回退。 */
  protected SdkTaskResult writeOut(I input, SdkTaskContext ctx, SdkRowResult counts)
      throws Exception {
    return null;
  }

  /** ADR-037 决策二 — 每攒多少行 {@code commit} 一次(断点保存 + 限流上报 + 取消检查)。默认 1000;Export 逐行写出,按行数攒批提交。 */
  protected int commitIntervalRows() {
    return 1000;
  }

  /**
   * ADR-037 决策一 — 计算当前<b>断点坐标</b>(已写出到的最后一行业务键)。默认返回空 Map。要断点续跑的租户应 override:返回最近写出行的排序键 / 主键,与
   * {@link #buildQuery} 的范围条件同坐标系。
   */
  protected Map<String, Object> breakPosition(I input, R lastRow) {
    return Map.of();
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
    // ADR-037 决策一:execute 开头读回断点。
    String taskKey = String.valueOf(ctx.taskId());
    Optional<SdkCheckpointState> resumed = ctx.checkpoint().load(taskKey);
    if (resumed.map(SdkCheckpointState::completed).orElse(false)) {
      return SdkTaskResult.ok("export already completed (resumed checkpoint), skipped");
    }
    SdkRowResult counts = new SdkRowResult();
    resumed.ifPresent(
        s -> {
          counts.addSuccess(s.succeedCount());
          ctx.commitCoordinator().restoreCounts(s.succeedCount(), s.failCount());
        });
    try {
      openSink(input, ctx);
      String q = buildQuery(input, ctx);
      int interval = Math.max(1, commitIntervalRows());
      long sinceCommit = 0;
      R lastRow = null;
      try (Stream<R> rows = streamRows(input, ctx, q)) {
        Iterator<R> it = rows.iterator();
        while (it.hasNext()) {
          R row = it.next();
          formatRow(input, ctx, row);
          counts.incSuccess();
          lastRow = row;
          if (++sinceCommit >= interval) {
            // ADR-037 决策二 + 三:断点保存 + 限流上报三合一;提交后命中取消则安全点抛停止。
            ctx.commitCoordinator().recordBatch(sinceCommit, 0);
            ctx.commit(breakPosition(input, row));
            sinceCommit = 0;
          }
        }
      }
      if (sinceCommit > 0) {
        ctx.commitCoordinator().recordBatch(sinceCommit, 0);
        ctx.commit(breakPosition(input, lastRow));
      }
      ctx.commitCoordinator().markCompleted(breakPosition(input, lastRow));
      SdkTaskResult explicit = writeOut(input, ctx, counts);
      if (explicit != null) {
        return explicit;
      }
      return result(input, counts, "exported " + counts.success() + " rows");
    } catch (SdkTaskStoppedException stopped) {
      throw stopped; // 决策三:协作取消穿透到模板顶层,业务不得吞。
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
