package com.example.batch.sdk.handler;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import com.example.batch.sdk.task.SdkTaskStoppedException;
import lombok.extern.slf4j.Slf4j;

/**
 * ADR-036 — SDK 五大业务模板的共同基类,提供模板方法 {@code validate → before → doExecute → after + finally
 * cleanup},把租户业务代码限制在"填钩子"层。
 *
 * <p>仍 {@code implements SdkTaskHandler}(ADR-035 协议契约不动),只是给租户更友好的写法层。{@link
 * #execute(SdkTaskContext)} 用 {@code final} 锁死执行序,子类不可改。
 *
 * <p>5 个 shape 子类:{@link SdkAbstractAtomicHandler} / {@link SdkAbstractImportHandler} / {@link
 * SdkAbstractExportHandler} / {@link SdkAbstractProcessHandler} / {@link
 * SdkAbstractDispatchHandler}。
 *
 * <p><b>边界</b>(ADR-035 §6 路径 3):租户进程持有所有 stage 状态,平台只看终态 + counts。本基类**不**接触平台 {@code file_record}
 * / {@code pipeline_instance} / {@code batch_day},也**不**做 stage 级生命周期事件(那会重暴露 pipeline 状态机)。
 */
@Slf4j
public abstract class SdkAbstractTaskHandler implements SdkTaskHandler {

  /** 模板方法 — final 锁死执行序。 */
  @Override
  public final SdkTaskResult execute(SdkTaskContext ctx) {
    boolean started = false;
    try {
      validate(ctx);
      before(ctx);
      started = true;
      SdkTaskResult r = doExecute(ctx);
      after(ctx, r);
      return r == null ? SdkTaskResult.fail("handler returned null SdkTaskResult") : r;
    } catch (SdkTaskStoppedException stopped) {
      // ADR-037 决策三:协作式取消停在已提交安全点 → cancelled 终态,而非 fail。
      log.info(
          "SDK handler {} stopped cooperatively (taskType={}, taskId={}) at {}",
          getClass().getSimpleName(),
          taskType(),
          ctx == null ? null : ctx.taskId(),
          stopped.breakPosition());
      return SdkTaskResult.cancelled(stopped.breakPosition());
    } catch (Throwable t) {
      log.error(
          "SDK handler {} failed (taskType={}, taskId={}): {}",
          getClass().getSimpleName(),
          taskType(),
          ctx == null ? null : ctx.taskId(),
          t.getMessage(),
          t);
      return SdkTaskResult.fail(
          t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(), t);
    } finally {
      if (started) {
        try {
          cleanup(ctx);
        } catch (Throwable cleanupEx) {
          log.warn(
              "SDK handler {} cleanup() failed: {}",
              getClass().getSimpleName(),
              cleanupEx.getMessage());
        }
      }
    }
  }

  /** 业务输入校验,失败抛异常 → 模板转 {@link SdkTaskResult#fail}。默认 no-op。 */
  protected void validate(SdkTaskContext ctx) {
    // 无操作
  }

  /** 资源 acquire(打开连接 / 占用 lease 等)。默认 no-op;抛异常被模板捕获转 fail。 */
  protected void before(SdkTaskContext ctx) {
    // 无操作
  }

  /**
   * 真业务执行。子类(或 shape 基类)实现。抛任何 {@link Throwable} 都被模板 catch 转 {@link SdkTaskResult#fail}。
   *
   * @return 不应返 null;返 null 会被转 fail("returned null")
   */
  protected abstract SdkTaskResult doExecute(SdkTaskContext ctx);

  /** doExecute 成功完成后调(异常路径不调)。默认 no-op。 */
  protected void after(SdkTaskContext ctx, SdkTaskResult result) {
    // 无操作
  }

  /** finally 资源 release(关连接 / 删临时文件 / 释 lease)。默认 no-op。 */
  protected void cleanup(SdkTaskContext ctx) {
    // 无操作
  }
}
