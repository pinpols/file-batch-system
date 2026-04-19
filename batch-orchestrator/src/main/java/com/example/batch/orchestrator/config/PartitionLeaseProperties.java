package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分片租约配置。
 *
 * <h3>C-2.1 A · 租约窗口覆盖 REPORT 重试</h3>
 *
 * <p>worker 执行完业务后调 {@code HttpTaskExecutionClient.report(...)}，该调用最多重试
 * {@code batch.worker.orchestrator-task.report-max-attempts}（默认 4）次，单次最长退避 5s，
 * 含网络超时合计最坏 ~20-25s。若 lease 过期早于 report 最终送达，orchestrator 侧会视为
 * worker 失活并重新派发 → 同一任务被执行两次（v3 C-2.1 · Report 失败→重复执行）。
 *
 * <p>本类的 {@link #expireSeconds} 默认 <b>120s</b>，基于最坏场景推算：
 * <ul>
 *   <li>report 重试窗口 ≤ 25s（4 × 5s backoff + 网络超时）
 *   <li>最近一次 lease renew 到执行结束最多 {@code batch.worker.lease.renew-interval-millis}=10s
 *   <li>业务余量 15s（TaskDispatchExecutor 后处理 / MDC 清理 / JVM GC 抖动）
 * </ul>
 * 合计 ≥ 50s，120s 留 ≈2.4× 安全倍率。若 ops 侧调整 report-max-attempts / max-backoff-millis，
 * 需同步确保 lease.expire-seconds ≥ 理论重试窗口 + 10s + 15s。
 *
 * <p>{@link #reclaimIntervalMillis} 控制 orchestrator 扫过期 lease 的节奏，独立于 TTL。
 */
@Data
@ConfigurationProperties(prefix = "batch.partition-lease")
public class PartitionLeaseProperties {

  /**
   * Lease TTL（秒）。默认 120s —— 覆盖 report 重试窗口 + 最近一次续期间隔 + 业务余量，
   * 见类级 javadoc 推算。<b>调整下限</b>：&lt; 60s 风险很大（与 reclaim 间隔差几乎等于 0）。
   */
  private long expireSeconds = 120L;

  /** orchestrator 侧 reclaim 扫描过期 lease 的节奏（毫秒）。 */
  private long reclaimIntervalMillis = 15000L;
}
