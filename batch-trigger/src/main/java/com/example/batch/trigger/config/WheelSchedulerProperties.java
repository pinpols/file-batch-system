package com.example.batch.trigger.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 时间轮 scheduler 配置(仅 {@code batch.trigger.scheduler-impl=wheel} 时生效)。
 *
 * <p>详见 {@code docs/architecture/quartz-replacement-design.md}:
 *
 * <ul>
 *   <li>§5.4 Wheel 配置项
 *   <li>§4.3 stale marker 接管阈值
 *   <li>§6 Failover fast-path
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "batch.trigger.wheel")
public class WheelSchedulerProperties {

  /** 时间轮 tick 间隔(ms)。Netty HashedWheelTimer 默认 100ms。 */
  private int tickMillis = 100;

  /** 时间轮 bucket 数。512 buckets × 100ms = 51.2s 一圈。 */
  private int bucketCount = 512;

  /** 滑动窗口大小(秒):每次扫库 push 进 wheel 的"未来 N 秒内即将 fire 的 trigger"。 */
  private int slidingWindowSeconds = 300;

  /** 滑动窗口扫库间隔(秒)。默认 60s 一次。 */
  private int slidingWindowScanIntervalSeconds = 60;

  /** ShedLock leader 锁名。 */
  private String leaderLockName = "trigger_wheel_leader";

  /** ShedLock lockAtMostFor(秒)。leader 崩溃后锁释放最长等待时间。 */
  private int leaderLockAtMostForSeconds = 120;

  /** ShedLock lockAtLeastFor(秒)。leader GC pause 容忍下限。 */
  private int leaderLockAtLeastForSeconds = 30;

  /** stale marker 接管阈值(秒):marker 超过此时间未释放,视为 leader 崩溃,可被新 leader 接管。 */
  private int staleMarkerThresholdSeconds = 300;

  /** stale marker 释放扫描周期(秒)。 */
  private int staleMarkerReleaseIntervalSeconds = 120;

  /** 单次扫库 limit(防一次拉太多)。 */
  private int scanBatchSize = 1000;

  /** misfire 阈值(秒):next_fire_time 比 now() 早超过此值视为 misfire。 */
  private int misfireThresholdSeconds = 60;

  /** misfire AUTO 策略 catch-up 限流(每秒最多 fire 次数);防雪崩。 */
  private double catchUpRatePerSecond = 10.0;

  /** leader instance id;默认从 hostname + pid 派生,显式 set 时覆盖。 */
  private String leaderInstanceId;
}
