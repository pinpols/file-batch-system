package io.github.pinpols.batch.console.shared.usage;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 操作审计派生使用量的有界异步调度。
 *
 * <p>审计事务提交后的 {@code afterCommit} 仍绑定着事务连接；在同一线程再开 {@code REQUIRES_NEW} 使用量事务会同时占用两个连接，小池下会
 * 相互等待直到借用超时。这里把派生写提交到独立的有界线程池，让审计线程先归还连接；队列满时丢弃派生写并告警，操作审计仍是事实源。
 */
@Slf4j
@Component
public class ConsoleUsageProjectionDispatcher {

  /** 有界线程池的 bean 名；队列满时由 {@link #submit} 丢弃并告警。 */
  public static final String EXECUTOR_BEAN_NAME = "usageTaskExecutor";

  private final Executor executor;

  public ConsoleUsageProjectionDispatcher(@Qualifier(EXECUTOR_BEAN_NAME) Executor executor) {
    this.executor = executor;
  }

  public void submit(Runnable projection) {
    try {
      executor.execute(projection);
    } catch (RejectedExecutionException rejected) {
      log.warn("usage projection dropped because the bounded queue is full");
    }
  }
}
