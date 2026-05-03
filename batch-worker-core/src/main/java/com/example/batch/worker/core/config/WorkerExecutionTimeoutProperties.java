package com.example.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P0-1 worker 任务执行超时治理配置 ({@code batch.worker.execution})。
 *
 * <p>解决问题: plugin 死循环 / 长 SQL 卡住 → orchestrator 标 TIMED_OUT, worker 线程仍占着 → Semaphore permit 永 不释放
 * → worker 容量永久缩水. 通过把执行 submit 到独立 pool + 限时 wait + Future.cancel(true) 强中断, 让 listener 线程 始终能在
 * timeout 后继续派下一个 task.
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.execution")
public class WorkerExecutionTimeoutProperties {

  /**
   * 执行 pool 线程数. 必须 ≥ {@code batch.worker.max-concurrent-tasks}, 否则 listener 拿到许可却抢不到 pool 线程会自我堵死.
   */
  private int poolSize = 16;

  /** 默认 task 超时 (秒). 当 EffectiveTaskConfig.timeoutSeconds 为 null/0 时兜底. 默认 30 分钟. */
  private long defaultTimeoutSeconds = 1800L;

  /** 上限 (秒). 超过此值即截断 (防呆: 配错 timeout=999999 把 worker 搞死). */
  private long maxTimeoutSeconds = 7200L;

  /**
   * future.cancel(true) 后宽限时间 (秒). 协作式 cancel: Thread.interrupt 后给业务线程 N 秒主动退出; 超过即放弃跟踪 (线程泄漏入
   * metric worker.task.execution.thread.leaked.total).
   */
  private long cancelGraceSeconds = 30L;
}
