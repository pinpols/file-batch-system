package com.example.batch.sdk.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
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
   * worker_registry.build_id,运维可据此区分同一 SDK 版本下不同业务构建。<b>不要放敏感信息</b>(会写入数据库并对运维可见)。
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

  // ─── R3-4 时序校验严格度开关(Round-2 P0 #4)─────────────────────────────────
  /**
   * R3-4:启动期时序 4 规则(hb>=1s / lease>=5s / lease<=hb*3 / http<=hb/2)的严格度开关。
   *
   * <ul>
   *   <li>{@code true}(默认,保持原行为)— 任一违反 → 抛 {@link IllegalStateException} fail-fast,进程异常退出 让 K8s /
   *       systemd 拉起 + 运维介入。生产环境推荐
   *   <li>{@code false} — 违反时 {@code log.warn(...)} 但不抛,client 仍可 build。 适用于运维降级窗口
   *       (例如已知配置稍偏但短期内可接受、避免 K8s 重启循环);<b>临时口子,不建议长期开启</b>
   * </ul>
   *
   * <p>env 覆盖:{@code BATCH_SDK_STRICT_TIMING=false} 可一键降级(默认 true)。
   */
  @Default boolean strictTimingValidation = true;

  /**
   * 从环境变量构造配置(默认前缀 {@code BATCH_SDK_})—— 租户无需在 {@code main()} 里手写一堆 {@code System.getenv}。
   *
   * <p>必填:{@code <prefix>BASE_URL / TENANT_ID / WORKER_CODE / KAFKA_BOOTSTRAP / KAFKA_TOPIC_PATTERN
   * / KAFKA_GROUP_ID}。可选:{@code API_KEY / BUILD_ID / MAX_CONCURRENT_TASKS /
   * HEARTBEAT_INTERVAL_SECONDS / HTTP_TIMEOUT_SECONDS / KAFKA_SECURITY_PROTOCOL /
   * KAFKA_SASL_MECHANISM / KAFKA_SASL_JAAS_CONFIG}。缺必填项一次性报全。
   */
  public static BatchPlatformClientConfig fromEnv() {
    return fromEnv("BATCH_SDK_");
  }

  /** 同 {@link #fromEnv()},自定义前缀。 */
  public static BatchPlatformClientConfig fromEnv(String prefix) {
    return fromEnv(prefix, System::getenv);
  }

  /** 可注入环境查找函数的版本(测试用)。 */
  static BatchPlatformClientConfig fromEnv(String prefix, UnaryOperator<String> env) {
    List<String> missing = new ArrayList<>();
    String baseUrl = required(env, prefix, "BASE_URL", missing);
    String tenantId = required(env, prefix, "TENANT_ID", missing);
    String workerCode = required(env, prefix, "WORKER_CODE", missing);
    String kafkaBootstrap = required(env, prefix, "KAFKA_BOOTSTRAP", missing);
    String kafkaTopicPattern = required(env, prefix, "KAFKA_TOPIC_PATTERN", missing);
    String kafkaGroupId = required(env, prefix, "KAFKA_GROUP_ID", missing);
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(
          "missing required env vars: " + String.join(", ", missing));
    }

    BatchPlatformClientConfigBuilder builder =
        builder()
            .baseUrl(baseUrl)
            .tenantId(tenantId)
            .workerCode(workerCode)
            .kafkaBootstrap(kafkaBootstrap)
            .kafkaTopicPattern(kafkaTopicPattern)
            .kafkaGroupId(kafkaGroupId)
            .apiKey(env.apply(prefix + "API_KEY"))
            .buildId(env.apply(prefix + "BUILD_ID"))
            .kafkaSecurityProtocol(env.apply(prefix + "KAFKA_SECURITY_PROTOCOL"))
            .kafkaSaslMechanism(env.apply(prefix + "KAFKA_SASL_MECHANISM"))
            .kafkaSaslJaasConfig(env.apply(prefix + "KAFKA_SASL_JAAS_CONFIG"));

    String maxConcurrent = env.apply(prefix + "MAX_CONCURRENT_TASKS");
    if (maxConcurrent != null && !maxConcurrent.isBlank()) {
      builder.maxConcurrentTasks(Integer.parseInt(maxConcurrent.trim()));
    }
    String heartbeat = env.apply(prefix + "HEARTBEAT_INTERVAL_SECONDS");
    if (heartbeat != null && !heartbeat.isBlank()) {
      builder.heartbeatInterval(Duration.ofSeconds(Long.parseLong(heartbeat.trim())));
    }
    String httpTimeout = env.apply(prefix + "HTTP_TIMEOUT_SECONDS");
    if (httpTimeout != null && !httpTimeout.isBlank()) {
      builder.httpTimeout(Duration.ofSeconds(Long.parseLong(httpTimeout.trim())));
    }
    // Lane I:lease 续约间隔也开放 env 覆盖,方便运维联调 heartbeat / lease 配比
    String leaseRenew = env.apply(prefix + "LEASE_RENEW_INTERVAL_SECONDS");
    if (leaseRenew != null && !leaseRenew.isBlank()) {
      builder.leaseRenewInterval(Duration.ofSeconds(Long.parseLong(leaseRenew.trim())));
    }
    // R3-4(Round-2 P0 #4):时序校验严格度开关,env 默认 true(保持 fail-fast)。
    // 仅当显式设为 false / 0 / no / off 时降级为 WARN-only。
    String strictTiming = env.apply(prefix + "STRICT_TIMING");
    if (strictTiming != null && !strictTiming.isBlank()) {
      builder.strictTimingValidation(parseBoolean(strictTiming.trim()));
    }

    BatchPlatformClientConfig config = builder.build();
    config.validate();
    return config;
  }

  /**
   * R3-4:解析 {@code BATCH_SDK_STRICT_TIMING} 等布尔 env。 显式 {@code false / 0 / no / off}(忽略大小写)→ {@code
   * false};其它(含 {@code true / 1 / yes / on / 任意非法值})→ {@code true}, 体现"默认 strict、降级需显式声明" 的安全偏好。
   */
  static boolean parseBoolean(String raw) {
    String v = raw.toLowerCase();
    return !("false".equals(v) || "0".equals(v) || "no".equals(v) || "off".equals(v));
  }

  private static String required(
      UnaryOperator<String> env, String prefix, String key, List<String> missing) {
    String value = env.apply(prefix + key);
    if (value == null || value.isBlank()) {
      missing.add(prefix + key);
      return null;
    }
    return value.trim();
  }

  public void validate() {
    Objects.requireNonNull(baseUrl, "baseUrl");
    if (baseUrl.endsWith("/")) {
      throw new IllegalArgumentException("baseUrl must not end with '/': " + baseUrl);
    }
    if (maxConcurrentTasks < 1 || maxConcurrentTasks > 64) {
      throw new IllegalArgumentException(
          "maxConcurrentTasks must be 1..64, got " + maxConcurrentTasks);
    }
    validateTimings();
  }

  /**
   * Lane I:启动期 cross-field 时序校验。任何一条不满足 → 抛 {@link IllegalStateException} fail-fast, 让进程以非 0
   * 退出码异常退出,由 K8s / systemd 拉起 + 运维介入。
   *
   * <p>规则(均为务实经验值,运维问题里高频出现的):
   *
   * <ul>
   *   <li>{@code heartbeatInterval >= 1s} — 防止极端配置刷爆 orch
   *   <li>{@code leaseRenewInterval >= 5s} — 同上
   *   <li>{@code leaseRenewInterval <= heartbeatInterval × 3} — 避免 lease 续约比心跳还少,task 半路被 orch
   *       误判租约过期回收。注意:server 端真正的 lease TTL SDK 不知道,这里用 heartbeatInterval × 3 做"代理上限",经验上 orch 默认
   *       TTL ~3min、heartbeat 默认 30s 的组合下安全;若 orch TTL 调更短,租户应同步调小 heartbeatInterval
   *   <li>{@code httpTimeout <= heartbeatInterval / 2} — 避免单次心跳超时排队后 backlog 把 scheduler 拖死
   * </ul>
   */
  private void validateTimings() {
    long hbMs = heartbeatInterval.toMillis();
    long leaseMs = leaseRenewInterval.toMillis();
    long httpMs = httpTimeout.toMillis();
    if (hbMs < 1_000L) {
      reportTimingViolation(
          "BatchPlatformClient config invalid: heartbeatInterval="
              + heartbeatInterval
              + " must be >= 1s (current "
              + hbMs
              + "ms; suggest >= 5s for prod)");
    }
    if (leaseMs < 5_000L) {
      reportTimingViolation(
          "BatchPlatformClient config invalid: leaseRenewInterval="
              + leaseRenewInterval
              + " must be >= 5s (current "
              + leaseMs
              + "ms; suggest 30s..60s for prod)");
    }
    long leaseUpperMs = hbMs * 3L;
    if (leaseMs > leaseUpperMs) {
      reportTimingViolation(
          "BatchPlatformClient config invalid: leaseRenewInterval="
              + leaseRenewInterval
              + " must be <= heartbeatInterval × 3 ("
              + leaseUpperMs
              + "ms) — 否则 in-flight task 可能被 orch 误判租约过期回收;"
              + "suggest leaseRenewInterval ≈ 2 × heartbeatInterval");
    }
    long httpUpperMs = hbMs / 2L;
    if (httpMs > httpUpperMs) {
      reportTimingViolation(
          "BatchPlatformClient config invalid: httpTimeout="
              + httpTimeout
              + " must be <= heartbeatInterval / 2 ("
              + httpUpperMs
              + "ms) — 否则心跳超时会堆积阻塞 scheduler;"
              + "suggest 调大 heartbeatInterval 或调小 httpTimeout");
    }
  }

  /**
   * R3-4(Round-2 P0 #4):按 {@link #strictTimingValidation} 决定抛异常 fail-fast 还是 WARN 降级。
   *
   * <ul>
   *   <li>{@code strictTimingValidation=true}(默认) → 抛 {@link IllegalStateException},进程异常退出
   *   <li>{@code strictTimingValidation=false} → {@code log.warn(...)},client 仍可 build, 供运维降级窗口避免
   *       K8s 重启循环
   * </ul>
   */
  private void reportTimingViolation(String message) {
    if (strictTimingValidation) {
      throw new IllegalStateException(message);
    }
    log.warn(
        "[R3-4 WARN-mode] strictTimingValidation=false, 跳过 fail-fast — {} "
            + "(临时口子;尽快修正 config,strictTimingValidation 不建议长期 false)",
        message);
  }
}
