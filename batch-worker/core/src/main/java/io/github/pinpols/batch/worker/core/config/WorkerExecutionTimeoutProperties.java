package io.github.pinpols.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P0-1 worker 任务执行超时治理配置 ({@code batch.worker.execution})。
 *
 * <p>解决问题: plugin 无限循环 / 长 SQL 卡住 → orchestrator 标 TIMED_OUT, worker 线程仍占着 → Semaphore permit 永 不释放
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

  /** 默认 task 超时 (秒). 当 EffectiveTaskConfig.timeoutSeconds 为 null/0 时回退. 默认 30 分钟. */
  private long defaultTimeoutSeconds = 1800L;

  /** 上限 (秒). 超过此值即截断 (防配置错误: timeout=999999 会长期占用 worker 执行线程). */
  private long maxTimeoutSeconds = 7200L;

  /**
   * future.cancel(true) 后宽限时间 (秒). 协作式 cancel: Thread.interrupt 后给业务线程 N 秒主动退出; 超过即放弃跟踪 (线程泄漏入
   * metric worker.task.execution.thread.leaked.total).
   */
  private long cancelGraceSeconds = 30L;

  /**
   * Spring 容器销毁时执行池的 graceful 等待时间 (秒). Worker graceful shutdown 会先等待活跃任务 drain, 这里仅处理残留任务
   * 和不响应中断的业务线程, 默认保持短等待以避免进程退出被阻塞过久.
   */
  private long shutdownGraceSeconds = 5L;

  /**
   * 执行线程是否使用 daemon 线程. 生产默认 false, 避免 JVM 退出时直接丢弃仍在执行的业务任务; E2E/测试可设为 true, 防止测试 fork
   * 退出阶段被残留执行线程拖住.
   */
  private boolean daemonThreads = false;
}
