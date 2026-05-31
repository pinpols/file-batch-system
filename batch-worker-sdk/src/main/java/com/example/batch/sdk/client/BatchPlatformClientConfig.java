package com.example.batch.sdk.client;

import java.time.Duration;
import java.util.Objects;
import lombok.Builder;
import lombok.Value;

/**
 * SDK 连接配置 — 业务方 build 出 {@link BatchPlatformClient} 时填。
 *
 * <p>关键字段:
 *
 * <ul>
 *   <li>{@link #baseUrl}:平台 console-api / orchestrator 暴露的 {@code /api/internal/*} 根 URL
 *   <li>{@link #apiKey}:租户在 console "我的 Worker" 页申请的 API Key(P2 上线后启用,P1 阶段可为 null)
 *   <li>{@link #tenantId}:本 worker 归属租户
 *   <li>{@link #workerCode}:worker 标识,跨重启稳定;同 tenant 内唯一
 *   <li>{@link #kafkaBootstrap}:Kafka bootstrap servers
 *   <li>{@link #kafkaTopicPattern}:订阅哪些 topic(支持 wildcard,如 {@code batch.task.dispatch.<tenant>.*})
 *   <li>{@link #kafkaGroupId}:consumer group id
 * </ul>
 */
@Value
@Builder
public class BatchPlatformClientConfig {

  @lombok.NonNull String baseUrl;
  String apiKey;
  @lombok.NonNull String tenantId;
  @lombok.NonNull String workerCode;

  @lombok.NonNull String kafkaBootstrap;
  @lombok.NonNull String kafkaTopicPattern;
  @lombok.NonNull String kafkaGroupId;

  /** HTTP 调用超时(connect + read)。默认 10s。 */
  @lombok.Builder.Default Duration httpTimeout = Duration.ofSeconds(10);

  /** Heartbeat 上报间隔。默认 30s。 */
  @lombok.Builder.Default Duration heartbeatInterval = Duration.ofSeconds(30);

  /** 单 worker 进程最大并发处理任务数(线程池大小)。默认 4。 */
  @lombok.Builder.Default int maxConcurrentTasks = 4;

  /** Kafka poll 间隔。默认 200ms。 */
  @lombok.Builder.Default Duration kafkaPollInterval = Duration.ofMillis(200);

  public void validate() {
    Objects.requireNonNull(baseUrl, "baseUrl");
    if (baseUrl.endsWith("/")) {
      throw new IllegalArgumentException("baseUrl must not end with '/': " + baseUrl);
    }
    if (maxConcurrentTasks < 1 || maxConcurrentTasks > 64) {
      throw new IllegalArgumentException(
          "maxConcurrentTasks must be 1..64, got " + maxConcurrentTasks);
    }
  }
}
