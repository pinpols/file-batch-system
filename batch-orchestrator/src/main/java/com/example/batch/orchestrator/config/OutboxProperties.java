package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.outbox")
public class OutboxProperties {

    private int batchSize = 100;
    private long pollIntervalMillis = 5000L;
    private long retryDelaySeconds = 60L;
    private int maxRetryAttempts = 5;
    private String producerName = "batch-orchestrator";
    private String defaultTopic = "batch.outbox.event";

    /**
     * Outbox 投递熔断：当连续若干轮推进中出现失败，进入 cooldown，避免失败重试放大问题。
     */
    private boolean circuitBreakerEnabled = true;
    private int circuitBreakerFailureThresholdConsecutivePolls = 3;
    private long circuitBreakerCooldownMillis = 60000L;

    /**
     * Outbox 分片轮询配置。
     * <p>
     * shardTotal = 1（默认）：单实例模式，行为与未分片完全一致，ShedLock key 保持 "outbox_poll"。
     * shardTotal > 1：多实例并行模式，每个实例通过 shardIndex 认领独立分片，
     *   ShedLock key 变为 "outbox_poll_shard_{shardIndex}"，允许多实例同时推进不同租户的 Outbox。
     * <p>
     * 部署时每个 Orchestrator 副本须设置不同的 BATCH_OUTBOX_SHARD_INDEX（0 到 shardTotal-1）。
     */
    private int shardTotal = 1;
    private int shardIndex = 0;
}
