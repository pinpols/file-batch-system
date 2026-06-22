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
 * A.2 — typed Process 模板:tenant → tenant(transform 写回),把强类型入参与 ADR-036 Process 行流模板合流。
 *
 * <p>模板序:{@code selectInput → transform(逐行,返 null = skip) → 按 batch 累积 → upsert → 汇总}。 租户拿强类型入参
 * {@code P}(任务参数,如过滤条件 / 目标表),再走输入行 {@code IN} → 输出行 {@code OUT} 的转换。 复用 {@link SdkTypedParameters}
 * 解析入参(组合)。
 *
 * @param <P> 强类型任务入参(从 parameters 反序列化)
 * @param <IN> 输入行类型
 * @param <OUT> 输出行类型
 * @param <O> 业务结果(序列化进 output;返 null 则走计数器 output)
 */
public abstract class SdkAbstractTypedProcessHandler<P, IN, OUT, O> extends SdkAbstractTaskHandler {

  private final SdkTypedParameters<P> params;

  protected SdkAbstractTypedProcessHandler() {
    this(SdkTypedParameters.defaultObjectMapper());
  }

  protected SdkAbstractTypedProcessHandler(ObjectMapper objectMapper) {
    this.params =
        SdkTypedParameters.forHandler(objectMapper, this, SdkAbstractTypedProcessHandler.class, 0);
  }

  /** 读输入行(从租户表 select)。模板用 try-with-resources 关闭,保证背后的 {@code ResultSet} 在读完或异常时都释放。 */
  protected abstract Stream<IN> selectInput(P input, SdkTaskContext ctx) throws Exception;

  /** 单行转换 IN→OUT;返回 null 表示该行 skip(不写)。 */
  protected abstract OUT transform(P input, SdkTaskContext ctx, IN row) throws Exception;

  /** 批量 upsert 输出行到租户表。 */
  protected abstract void upsert(P input, SdkTaskContext ctx, List<OUT> batch) throws Exception;

  /** 批大小,默认 500,可覆盖。 */
  protected int batchSize() {
    return 500;
  }

  /**
   * ADR-037 决策一 — 计算本批<b>断点坐标</b>。默认返回空 Map(不续跑,仅有上报 / 取消语义)。要断点续跑的租户应 override:返回本批最后一行的业务主键, 与
   * {@link #selectInput} 的 {@code WHERE key > :breakPosition} 同坐标系。
   */
  protected Map<String, Object> breakPosition(P input, List<OUT> batch) {
    return Map.of();
  }

  /** 汇总成业务结果 {@code O};默认返 null。 */
  protected O summarize(P input, SdkRowResult counts) {
    return null;
  }

  @Override
  protected final SdkTaskResult doExecute(SdkTaskContext ctx) {
    P input;
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
      return SdkTaskResult.ok("process already completed (resumed checkpoint), skipped");
    }
    SdkRowResult counts = new SdkRowResult();
    resumed.ifPresent(s -> ctx.commitCoordinator().restoreCounts(s.succeedCount(), s.failCount()));
    try {
      List<OUT> buf = new ArrayList<>(batchSize());
      try (Stream<IN> rows = selectInput(input, ctx)) {
        Iterator<IN> it = rows.iterator();
        while (it.hasNext()) {
          OUT out = transform(input, ctx, it.next());
          if (out != null) {
            buf.add(out);
            counts.incSuccess();
          } else {
            counts.incSkipped();
          }
          if (buf.size() >= batchSize()) {
            flush(input, ctx, buf);
          }
        }
      }
      if (!buf.isEmpty()) {
        flush(input, ctx, buf);
      }
      ctx.commitCoordinator().markCompleted(breakPosition(input, List.of()));
      return result(input, counts, "processed " + counts.success() + " rows");
    } catch (SdkTaskStoppedException stopped) {
      throw stopped; // 决策三:协作取消穿透到模板顶层,业务不得吞。
    } catch (Exception e) {
      return SdkTaskResult.fail(e);
    }
  }

  private void flush(P input, SdkTaskContext ctx, List<OUT> buf) throws Exception {
    upsert(input, ctx, buf);
    // ADR-037 决策二 + 三:业务写 + 断点保存 + 限流上报三合一;提交后命中取消则在安全点抛停止。
    ctx.commitCoordinator().recordBatch(buf.size(), 0);
    ctx.commit(breakPosition(input, buf));
    buf.clear();
  }

  private SdkTaskResult result(P input, SdkRowResult counts, String defaultMessage) {
    O output = summarize(input, counts);
    if (output == null) {
      return SdkTaskResult.ok(defaultMessage, counts.toOutput());
    }
    return SdkTaskResult.ok(defaultMessage, params.toOutputMap(output));
  }
}
