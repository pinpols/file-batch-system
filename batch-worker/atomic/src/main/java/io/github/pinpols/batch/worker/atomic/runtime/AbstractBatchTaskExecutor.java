package io.github.pinpols.batch.worker.atomic.runtime;

import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.spi.task.BatchTaskExecutor;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import lombok.extern.slf4j.Slf4j;

/**
 * ADR-035 §9 — 原子任务执行器可选基类,提供模板方法 {@code validate → before → doExecute → after + finally cleanup}。
 *
 * <p>本类**只让 batch-worker-atomic 内的执行器 opt-in extends**(SPI-only;pipeline 4 个 worker 仍直接 {@code
 * implements BatchTaskExecutor} 不动,零影响)。新增 / 重构 原子任务执行器时可继承本类拿前置 / 后置 / cleanup 钩子。
 *
 * <p>**红线**(ADR-035 §9):
 *
 * <ul>
 *   <li>钩子是 opt-in 的(不在接口加 default 方法),要全局能力请走 SDK 侧扩展点
 *   <li>{@link #before / #after / #cleanup} 不应是多 stage 状态机(那是 pipeline 的边界)
 *   <li>validate、before、doExecute 和 after 抛出的 Exception 由 {@link #execute(TaskContext)} 转为失败结果；
 *       cleanup 的 Exception 记录日志，Error 不在此处捕获
 * </ul>
 *
 * <p>典型用法:
 *
 * <pre>{@code
 * @Component
 * public class MyAtomicExecutor extends AbstractBatchTaskExecutor {
 *   @Override
 *   public String taskType() {
 *     return "my_type";
 *   }
 *
 *   @Override
 *   protected void validate(TaskContext ctx) { ... }
 *
 *   @Override
 *   protected TaskResult doExecute(TaskContext ctx) { ... }
 *
 *   @Override
 *   protected void cleanup(TaskContext ctx) { ... }  // before 成功后必跑,即使 doExecute 抛异常
 * }
 * }</pre>
 */
@Slf4j
public abstract class AbstractBatchTaskExecutor implements BatchTaskExecutor {

  /** 模板方法:validate → before → doExecute → after；before 成功后执行 finally cleanup。 */
  @Override
  public final TaskResult execute(TaskContext ctx) {
    boolean started = false;
    try {
      validate(ctx);
      before(ctx);
      started = true;
      TaskResult result = doExecute(ctx);
      after(ctx, result);
      return result == null ? TaskResult.fail("executor returned null TaskResult", null) : result;
    } catch (Exception t) {
      String executorName = this.getClass().getSimpleName();
      String currentTaskType = taskType();
      String taskId = EmptyChecks.isNull(ctx) ? null : ctx.taskInstanceId();
      String causeSummary = SwallowedExceptionLogger.summary(t);
      log.error(
          "SPI executor {} failed (taskType={}, taskId={}): {}",
          executorName,
          LogSanitizer.value(currentTaskType),
          LogSanitizer.value(taskId),
          causeSummary);
      String failureMessage =
          EmptyChecks.isNull(t.getMessage()) ? t.getClass().getSimpleName() : t.getMessage();
      return TaskResult.fail(failureMessage, t);
    } finally {
      if (started) {
        try {
          cleanup(ctx);
        } catch (Exception cleanupEx) {
          // cleanup 异常不掩盖原结果,只 log
          String executorName = this.getClass().getSimpleName();
          String causeSummary = SwallowedExceptionLogger.summary(cleanupEx);
          log.warn("SPI executor {} cleanup() failed: {}", executorName, causeSummary);
        }
      }
    }
  }

  /** 业务输入合法性校验,失败抛 {@link IllegalArgumentException} 让模板方法转 {@link TaskResult#fail}。 默认 no-op。 */
  protected void validate(TaskContext ctx) {
    // 无操作
  }

  /** 资源 acquire(打开连接 / 占用 lease 等)。默认 no-op;抛异常会被模板方法捕获转 fail。 */
  protected void before(TaskContext ctx) {
    // 无操作
  }

  /**
   * 真实业务执行。子类必须实现。抛出的 {@link Exception} 会由模板方法转换为失败结果，{@link Error} 继续传播。
   *
   * @return 不应返 null;返 null 会被转 {@link TaskResult#fail("executor returned null")}
   */
  protected abstract TaskResult doExecute(TaskContext ctx);

  /** doExecute 成功完成后调用(异常路径不调)。默认 no-op。 注意:写副作用(如 outbox)应放 doExecute 内事务,不要放这里。 */
  protected void after(TaskContext ctx, TaskResult result) {
    // 无操作
  }

  /** finally 资源 release(关连接 / 删临时文件 / 释 lease 等)。默认 no-op。 */
  protected void cleanup(TaskContext ctx) {
    // 无操作
  }
}
