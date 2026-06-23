package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.outbox")
public class OutboxProperties {

  private int batchSize = 100;

  /** 最大轮询间隔（空闲时退避上限），单位毫秒。 同时保留原配置键 {@code batch.outbox.poll-interval-millis} 的语义，向后兼容。 */
  private long pollIntervalMillis = 5000L;

  /** 最小轮询间隔（积压时加速下限），单位毫秒。 当上一轮有任何事件被处理时，立即以此间隔调度下一轮。 */
  private long minPollIntervalMillis = 200L;

  /** 空闲退避系数：连续空闲时，每轮间隔乘以此系数，直至达到 {@link #pollIntervalMillis}。 */
  private double backoffMultiplier = 1.5;

  /** 失败重试基础延迟(秒);第 N 次失败的实际延迟 = base × multiplier^(N-1),封顶 {@link #retryMaxDelaySeconds}。 */
  private long retryDelaySeconds = 60L;

  private int maxRetryAttempts = 5;

  /**
   * 失败重试指数退避倍数(2026-05-01 加,默认 2.0 = 翻倍退避)。
   *
   * <p>避免固定 60s 间隔下,同 batch 失败的多条事件每 60s 同步重试形成 thundering herd。设 1.0 即关闭退避(回到旧行为)。
   */
  private double retryBackoffMultiplier = 2.0;

  /** 失败重试单次延迟封顶(秒,默认 600 = 10 min);防止 attempt 大时退避到天文数字。 */
  private long retryMaxDelaySeconds = 600L;

  /**
   * 失败重试 jitter 比例(0~1,默认 0.2 = ±20%)。每次计算 nextRetryAt 时在退避值上抖动 ±jitter 比例,打散 herd 重试时间点。设 0 即关闭
   * jitter。
   */
  private double retryJitterRatio = 0.2;

  private String producerName = "batch-orchestrator";
  private String defaultTopic = "batch.outbox.event";

  /** Outbox 投递熔断：当连续若干轮推进中出现失败，进入 cooldown，避免失败重试放大问题。 */
  private boolean circuitBreakerEnabled = true;

  private int circuitBreakerFailureThresholdConsecutivePolls = 3;
  private long circuitBreakerCooldownMillis = 60000L;

  /**
   * Outbox 分片轮询配置。
   *
   * <p>shardTotal = 1（默认）：单实例模式，行为与未分片完全一致，ShedLock key 保持 "outbox_poll"。 shardTotal >
   * 1：多实例并行模式，每个实例通过 shardIndex 认领独立分片， ShedLock key 变为
   * "outbox_poll_shard_{shardIndex}"，允许多实例同时推进不同租户的 Outbox。
   *
   * <p>部署时每个 Orchestrator 副本须设置不同的 BATCH_OUTBOX_SHARD_INDEX（0 到 shardTotal-1）。
   */
  /**
   * PUBLISHING 状态最大驻留时长（秒）。
   *
   * <p>若 outbox_event 在 PUBLISHING 状态超过此阈值，说明上一轮 markPublishing 后 Kafka 投递失败
   * 且未能正常回退状态。轮询器会在每轮开始前将这些滞留事件重置为 FAILED，避免永久长期停滞。
   */
  private long publishingTimeoutSeconds = 120L;

  private int shardTotal = 1;

  private int shardIndex = 0;

  /**
   * Shard 分配来源：
   *
   * <ul>
   *   <li>{@link ShardingMode#STATIC}（默认）：读 {@link #shardTotal} / {@link #shardIndex} ENV， 扩缩容需
   *       helm upgrade + 滚动重启
   *   <li>{@link ShardingMode#DYNAMIC}：Redis 协调活跃 Pod，HPA 扩缩时自动 rebalance
   * </ul>
   */
  private ShardingMode shardingMode = ShardingMode.STATIC;

  private Sharding sharding = new Sharding();

  public enum ShardingMode {
    STATIC,
    DYNAMIC
  }

  /** Sharding 协调子配置（DYNAMIC 模式下生效）。 */
  @Data
  public static class Sharding {
    /** 心跳间隔（毫秒），每个 Pod 按此频率把自己写进 Redis 存活集合。 */
    private long heartbeatIntervalMs = 5000L;

    /** 成员 TTL（毫秒）；超过此时长未心跳的 Pod 被视为死亡，从集合移除。 */
    private long memberTtlMs = 30000L;

    /** 当前 Pod 的稳定标识；留空时自动用 POD_NAME（K8s Downward API）或 hostname。 */
    private String memberId = "";
  }
}
