package io.github.pinpols.batch.common.spi.task;

/**
 * Atomic Task SPI — 单步原子任务执行契约,加新任务类型只需实现本接口 + 注册即可。
 *
 * <p>两种注册方式(并存):
 *
 * <ol>
 *   <li><b>Spring bean</b>(主路径):标 {@code @Component},自动被 {@link BatchTaskExecutorRegistry} 收集
 *   <li><b>ServiceLoader</b>(纯 jar 插件):在 {@code
 *       META-INF/services/io.github.pinpols.batch.common.spi.task.BatchTaskExecutor} 声明实现类
 * </ol>
 *
 * <p>不走 Pipeline 生命周期(无 stage / 无 step 循环)。需要多 stage 状态机的业务请走 {@code
 * AbstractPipelineStepExecutionAdapter}(worker-core 内的 Pipeline SPI)。
 *
 * <p>设计原则:实现类应是无状态 POJO,业务依赖通过构造器注入。不允许在 execute 内部直接访问 Spring {@code
 * ApplicationContext},所有需要的运行时依赖通过 {@link TaskContext} 受控传入。
 *
 * <p>完整设计见 {@code docs/design/task-spi-design.md}。
 */
public interface BatchTaskExecutor {

  /**
   * 全平台唯一标识,小写下划线(如 {@code "shell"} / {@code "sql"} / {@code "sftp_push"})。
   *
   * <p>同一 type 重复注册启动期 fail-fast(见 {@link BatchTaskExecutorRegistry})。
   */
  String taskType();

  /** 资源 / 行为声明,给 worker registry 路由匹配 + orchestrator 调度策略使用。 */
  TaskCapability capability();

  /**
   * 真正的执行入口。实现方负责自己的超时 / 资源隔离 / 审计语义。
   *
   * <p>异常应被 catch 并转 {@link TaskResult#fail(Throwable)};未捕获异常会被上层路由器回退转 failure response, 但失去业务级错误码
   * + 友好消息。
   */
  TaskResult execute(TaskContext ctx);

  /**
   * 可选:协作式取消。orchestrator 发取消信号时调用,实现方应尽快释放资源 + 中断执行。
   *
   * <p>默认 no-op(适用于不支持取消的任务,如已发出去的 HTTP 请求)。{@link TaskCapability#cancellable()} 必须跟本方法的实际行为一致。
   */
  default void cancel(String taskInstanceId) {
    // 默认不处理
  }
}
