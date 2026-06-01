package com.example.batch.sdk.client;

import java.time.Duration;
import java.util.Objects;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.NonNull;
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
@Builder(toBuilder = true)
public class BatchPlatformClientConfig {

  @NonNull String baseUrl;
  String apiKey;
  @NonNull String tenantId;
  @NonNull String workerCode;

  @NonNull String kafkaBootstrap;
  @NonNull String kafkaTopicPattern;
  @NonNull String kafkaGroupId;

  /**
   * SDK-P5-3 运行指纹:租户应用构建标识(可空)。建议由 CI 注入(如 git 短 SHA / 镜像 tag),register 时上报到平台
   * worker_registry.build_id,运维可据此区分同一 SDK 版本下不同业务构建。<b>不要放敏感信息</b>(会落库并对运维可见)。
   */
  String buildId;

  /** HTTP 调用超时(connect + read)。默认 10s。 */
  @Default Duration httpTimeout = Duration.ofSeconds(10);

  /** Heartbeat 上报间隔。默认 30s。 */
  @Default Duration heartbeatInterval = Duration.ofSeconds(30);

  /** 单 worker 进程最大并发处理任务数(线程池大小)。默认 4。 */
  @Default int maxConcurrentTasks = 4;

  /** Kafka poll 间隔。默认 200ms。 */
  @Default Duration kafkaPollInterval = Duration.ofMillis(200);

  /** in-flight 任务的 lease 续约间隔。应 < orchestrator 端 lease TTL(默认 ~3min)的 1/2。 */
  @Default Duration leaseRenewInterval = Duration.ofSeconds(60);

  // ─── P1-2 CLAIM 重试策略 ───────────────────────────────────────────────────
  /** CLAIM 收到 5xx / 传输错误时的最大额外重试次数(0 = 不重试,仅首试)。401/403 永远 fail-fast, 409 / 其它 4xx 永远不重试。默认 3。 */
  @Default int claimMax5xxRetries = 3;

  /** CLAIM 5xx 重试的基准退避(实际:{@code base * 2^attempt})。默认 200ms。 */
  @Default Duration claimRetryBaseDelay = Duration.ofMillis(200);

  // ─── P7-2 CLAIM/REPORT 连续 4xx fail-fast ──────────────────────────────────
  /**
   * P7-2:CLAIM / REPORT 连续(非鉴权、非 409)4xx 客户端错误达此阈值 → 标记 dispatcher FATAL(后续拒新派单、 {@code
   * isHealthy()} 报 false 让 K8s liveness probe 拉起)。任一次成功调用重置计数。 持续 4xx 通常意味 SDK 版本 / 契约不匹配, 重试无益,应
   * fail-fast 让运维介入。{@code 0} = 关闭此机制。默认 5。 鉴权 401/403 仍是首次即 fatal(P1-2),不受此阈值影响。
   */
  @Default int clientErrorFailFastThreshold = 5;

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
