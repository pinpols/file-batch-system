package com.example.batch.sdk.handler;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import com.example.batch.sdk.task.SdkTypedParameters;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
    try {
      SdkRowResult counts = new SdkRowResult();
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
            upsert(input, ctx, buf);
            buf.clear();
          }
        }
      }
      if (!buf.isEmpty()) {
        upsert(input, ctx, buf);
      }
      return result(input, counts, "processed " + counts.success() + " rows");
    } catch (Exception e) {
      return SdkTaskResult.fail(e);
    }
  }

  private SdkTaskResult result(P input, SdkRowResult counts, String defaultMessage) {
    O output = summarize(input, counts);
    if (output == null) {
      return SdkTaskResult.ok(defaultMessage, counts.toOutput());
    }
    return SdkTaskResult.ok(defaultMessage, params.toOutputMap(output));
  }
}
