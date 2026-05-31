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
 *   <li>{@link #baseUrl}:平台 console-api / orchestrator 暴露的 {@code /internal/*} 根 URL
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

  /** in-flight 任务的 lease 续约间隔。应 < orchestrator 端 lease TTL(默认 ~3min)的 1/2。 */
  @lombok.Builder.Default Duration leaseRenewInterval = Duration.ofSeconds(60);

  // ─── P3 Kafka SASL/SCRAM 鉴权 (per-tenant ACL) ─────────────────────────────
  /**
   * Kafka {@code security.protocol}。值如 {@code SASL_SSL / SASL_PLAINTEXT / PLAINTEXT};null 时不设(走
   * client 默认 {@code PLAINTEXT})。
   */
  String kafkaSecurityProtocol;

  /** Kafka {@code sasl.mechanism}。值如 {@code SCRAM-SHA-512 / SCRAM-SHA-256 / PLAIN}。 */
  String kafkaSaslMechanism;

  /**
   * Kafka {@code sasl.jaas.config}。例: {@code
   * org.apache.kafka.common.security.scram.ScramLoginModule required username="tenant-xyz"
   * password="<scram-pwd>";}。从 K8s Secret 或环境变量注入,**不要硬编码**。
   */
  String kafkaSaslJaasConfig;

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
