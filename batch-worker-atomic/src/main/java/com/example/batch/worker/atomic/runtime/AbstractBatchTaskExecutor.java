package com.example.batch.worker.atomic.runtime;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import lombok.extern.slf4j.Slf4j;

/**
 * ADR-035 §9 — SPI 执行器可选基类,提供模板方法 {@code validate → before → doExecute → after + finally cleanup}。
 *
 * <p>本类**只让 batch-worker-atomic 内的执行器 opt-in extends**(SPI-only;pipeline 4 个 worker 仍直接 {@code
 * implements BatchTaskExecutor} 不动,零影响)。新增 / 重构 SPI 执行器时可继承本类拿前置 / 后置 / cleanup 钩子。
 *
 * <p>**红线**(ADR-035 §9):
 *
 * <ul>
 *   <li>钩子是 opt-in 的(不在接口加 default 方法),要全局能力请走 SDK 侧扩展点
 *   <li>{@link #before / #after / #cleanup} 不应是多 stage 状态机(那是 pipeline 的边界)
 *   <li>钩子异常不应被吞掉,统一让 {@link #execute(TaskContext)} 的 catch-all 转成 {@link
 *       TaskResult#fail(Throwable)}
 * </ul>
 *
 * <p>典型用法:
 *
 * <pre>{@code
 * @Component
 * public class MyAtomicExecutor extends AbstractBatchTaskExecutor {
 *   @Override public String taskType() { return "my_type"; }
 *   @Override protected void validate(TaskContext ctx) { ... }
 *   @Override protected TaskResult doExecute(TaskContext ctx) { ... }
 *   @Override protected void cleanup(TaskContext ctx) { ... }  // 必跑,即使 doExecute 抛异常
 * }
 * }</pre>
 */
@Slf4j
public abstract class AbstractBatchTaskExecutor implements BatchTaskExecutor {

  /** 模板方法:固定流程 validate → before → doExecute → after + finally cleanup。 */
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
    } catch (Throwable t) {
      log.error(
          "SPI executor {} failed (taskType={}, taskId={}): {}",
          this.getClass().getSimpleName(),
          taskType(),
          ctx == null ? null : ctx.taskInstanceId(),
          t.getMessage(),
          t);
      return TaskResult.fail(
          t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(), t);
    } finally {
      if (started) {
        try {
          cleanup(ctx);
        } catch (Throwable cleanupEx) {
          // cleanup 异常不掩盖原结果,只 log
          log.warn(
              "SPI executor {} cleanup() failed: {}",
              this.getClass().getSimpleName(),
              cleanupEx.getMessage(),
              cleanupEx);
        }
      }
    }
  }

  /** 业务输入合法性校验,失败抛 {@link IllegalArgumentException} 让模板方法转 {@link TaskResult#fail}。 默认 no-op。 */
  protected void validate(TaskContext ctx) {
    // no-op
  }

  /** 资源 acquire(打开连接 / 占用 lease 等)。默认 no-op;抛异常会被模板方法捕获转 fail。 */
  protected void before(TaskContext ctx) {
    // no-op
  }

  /**
   * 真实业务执行。子类必须实现。抛任何 {@link Throwable} 都会被模板方法 catch 转 {@link TaskResult#fail}。
   *
   * @return 不应返 null;返 null 会被转 {@link TaskResult#fail("executor returned null")}
   */
  protected abstract TaskResult doExecute(TaskContext ctx);

  /** doExecute 成功完成后调用(异常路径不调)。默认 no-op。 注意:写副作用(如 outbox)应放 doExecute 内事务,不要放这里。 */
  protected void after(TaskContext ctx, TaskResult result) {
    // no-op
  }

  /** finally 资源 release(关连接 / 删临时文件 / 释 lease 等)。默认 no-op。 */
  protected void cleanup(TaskContext ctx) {
    // no-op
  }
}
