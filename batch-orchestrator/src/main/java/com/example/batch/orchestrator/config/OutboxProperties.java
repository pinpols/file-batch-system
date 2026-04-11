package com.example.batch.orchestrator.config;

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

    private long retryDelaySeconds = 60L;
    private int maxRetryAttempts = 5;
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
    private int shardTotal = 1;

    private int shardIndex = 0;
}
