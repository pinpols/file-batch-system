package com.example.batch.orchestrator.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分片租约配置。
 *
 * <h3>C-2.1 A · 租约窗口覆盖 REPORT 重试</h3>
 *
 * <p>worker 执行完业务后调 {@code HttpTaskExecutionClient.report(...)}，该调用最多重试 {@code
 * batch.worker.orchestrator-task.report-max-attempts}（默认 4）次，单次最长退避 5s， 含网络超时合计最坏 ~20-25s。若 lease
 * 过期早于 report 最终送达，orchestrator 侧会视为 worker 失活并重新派发 → 同一任务被执行两次（v3 C-2.1 · Report 失败→重复执行）。
 *
 * <p>本类的 {@link #expireSeconds} 默认 <b>120s</b>，基于最坏场景推算：
 *
 * <ul>
 *   <li>report 重试窗口 ≤ 25s（4 × 5s backoff + 网络超时）
 *   <li>最近一次 lease renew 到执行结束最多 {@code batch.worker.lease.renew-interval-millis}=10s
 *   <li>业务余量 15s（TaskDispatchExecutor 后处理 / MDC 清理 / JVM GC 抖动）
 * </ul>
 *
 * 合计 ≥ 50s，120s 留 ≈2.4× 安全倍率。若 ops 侧调整 report-max-attempts / max-backoff-millis， 需同步确保
 * lease.expire-seconds ≥ 理论重试窗口 + 10s + 15s。
 *
 * <p>{@link #reclaimIntervalMillis} 控制 orchestrator 扫过期 lease 的节奏，独立于 TTL。
 */
@Data
@Slf4j
@ConfigurationProperties(prefix = "batch.partition-lease")
public class PartitionLeaseProperties {

  /**
   * R3-P2-10 / S1-4：启动期断言 lease.expire-seconds &lt; kafka max-poll-interval-ms。
   *
   * <p>背景：worker 消费 dispatch topic 时，CLAIM 成功 → 进 RUNNING。若 max-poll-interval（默认 600s）期内 worker 没
   * commit offset，Kafka rebalance 把 partition 分给其他 worker。此时若 lease.expireSeconds 也已
   * 过期，orchestrator 侧 reclaim 会重派 task；rebalanced consumer 又来一次 CLAIM → 双执行。 严格关系：{@code
   * expireSeconds &lt; maxPollIntervalMs/1000}，留 50% 安全裕量。
   *
   * <p>{@code @Value} 注入 Kafka 容器配置；缺失时给保守默认 600s。
   */
  @Value("${spring.kafka.consumer.properties.max-poll-interval-ms:600000}")
  private int maxPollIntervalMillisInjected = 600_000;

  @PostConstruct
  void assertLeaseVsMaxPollWindow() {
    long maxPollSec = maxPollIntervalMillisInjected / 1000L;
    if (expireSeconds >= maxPollSec) {
      throw new IllegalStateException(
          "FATAL: batch.partition-lease.expire-seconds ("
              + expireSeconds
              + "s) 必须 < spring.kafka.consumer.properties.max-poll-interval-ms ("
              + maxPollSec
              + "s)。"
              + " rebalance 窗口与 lease 过期同时发生会触发双执行（worker reclaim + Kafka 再派给新 consumer）。"
              + " 建议 expire-seconds ≤ max-poll-interval/2（当前推荐 ≤"
              + (maxPollSec / 2)
              + "s）。");
    }
    if (expireSeconds >= maxPollSec / 2) {
      log.warn(
          "batch.partition-lease.expire-seconds ({}s) 接近 max-poll-interval 一半 ({}s)；"
              + " rebalance 期间 lease 重叠风险升高，建议调小到 1/3 以下。",
          expireSeconds,
          maxPollSec);
    }
  }

  /**
   * Lease TTL（秒）。默认 120s —— 覆盖 report 重试窗口 + 最近一次续期间隔 + 业务余量， 见类级 javadoc 推算。<b>调整下限</b>：&lt; 60s
   * 风险很大（与 reclaim 间隔差几乎等于 0）。
   */
  private long expireSeconds = 120L;

  /** orchestrator 侧 reclaim 扫描过期 lease 的节奏（毫秒）。 */
  private long reclaimIntervalMillis = 15000L;

  /**
   * 是否装载 {@link com.example.batch.orchestrator.infrastructure.lease.PartitionLeaseReclaimScheduler}
   * 定时任务。集成测试建议关闭，避免首轮扫描与 claim/launch 主路径并发相互干扰。
   */
  private boolean reclaimSchedulerEnabled = true;

  /**
   * 单次 reclaim 扫描的最大处理数量。0 表示不限制（保留旧行为）。 默认 500：在 ShedLock 2 分钟上限内可舒适处理（每次 reclaim 约 2~3 次 SQL，500
   * 行 ~= 30s）。 命中上限会触发 warn，提示业务方 lease 阈值或 worker 容量异常。
   */
  private int reclaimBatchSize = 500;

  /**
   * 补充 sweeper：扫描"partition_status=READY 且 lease_expire_at IS NULL 但仍有 RUNNING task" 的死态分区，强制将关联
   * task 复位。新代码在 REQUIRES_NEW + 第二步 CAS 失败抛异常时已不再产生此态，仅清理升级前残留。 默认开启，每 5 分钟扫一次。
   */
  private boolean orphanSweepEnabled = true;

  /** 补充 sweeper 扫描间隔（毫秒）。 */
  private long orphanSweepIntervalMillis = 300000L;

  /** 死态分区的 grace 期（秒）：updated_at 早于 now-graceSeconds 才视为真死态，避免误伤 reset 后即将派发的瞬时窗口。 */
  private long orphanSweepGraceSeconds = 120L;

  /** 单次回退扫描处理上限。 */
  private int orphanSweepBatchSize = 200;
}
