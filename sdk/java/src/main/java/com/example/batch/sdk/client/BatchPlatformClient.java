package com.example.batch.sdk.client;

import com.example.batch.sdk.dispatcher.KafkaTaskConsumer;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.idempotent.SdkIdempotencyStore;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.scheduler.HeartbeatScheduler;
import com.example.batch.sdk.scheduler.LeaseRenewalScheduler;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskTypeDescriptor;
import com.example.batch.sdk.wire.RegisterRequest;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * SDK 主入口 — 业务方 build + register + start,框架负责后续派单 + claim + report。
 *
 * <p>典型使用:
 *
 * <pre>{@code
 * BatchPlatformClientConfig config = BatchPlatformClientConfig.builder()
 *     .baseUrl("https://batch.example.com")
 *     .apiKey(System.getenv("BATCH_API_KEY"))
 *     .tenantId("tenant-xyz")
 *     .workerCode("xyz-import-worker-1")
 *     .kafkaBootstrap("kafka.example.com:9092")
 *     .kafkaTopicPattern("batch.task.dispatch.tenant-xyz.*")
 *     .kafkaGroupId("tenant-xyz-import-workers")
 *     .build();
 *
 * BatchPlatformClient client = BatchPlatformClient.builder(config)
 *     .register(new MyImportHandler())
 *     .register(new MyExportHandler())
 *     .build();
 *
 * client.start();      // 注册 + 启动 Kafka consumer + heartbeat scheduler
 * Runtime.getRuntime().addShutdownHook(new Thread(client::stop));
 * }</pre>
 *
 * <p>详细 README:{@code batch-worker-sdk/README.md}。
 */
@Slf4j
public class BatchPlatformClient {

  private final BatchPlatformClientConfig config;
  private final Map<String, SdkTaskHandler> handlers;
  private final PlatformHttpClient httpClient;
  private final SdkIdempotencyStore idempotencyStore;
  private volatile boolean started = false;
  private TaskDispatcher dispatcher;
  private KafkaTaskConsumer kafkaConsumer;
  private Thread kafkaConsumerThread;
  private HeartbeatScheduler heartbeatScheduler;
  private LeaseRenewalScheduler leaseRenewalScheduler;

  private BatchPlatformClient(
      BatchPlatformClientConfig config,
      Map<String, SdkTaskHandler> handlers,
      SdkIdempotencyStore idempotencyStore) {
    config.validate();
    this.config = config;
    this.handlers = Map.copyOf(handlers);
    this.idempotencyStore = idempotencyStore;
    this.httpClient = new PlatformHttpClient(config);
  }

  public static Builder builder(BatchPlatformClientConfig config) {
    return new Builder(config);
  }

  /**
   * 启动 — 调 register API + 启动 Kafka consumer + heartbeat / lease scheduler。
   *
   * <p>P7-3:register 失败 → 抛 {@link RuntimeException}(此时尚未启动任何后台线程)。调用方<b>不应吞掉</b>此异常 —— 让它传播出
   * {@code main} 使进程以非 0 退出码结束,K8s / systemd 据此重启拉起(register 失败通常是平台不可达 / apiKey 失效 / 配置错误,重启 +
   * 运维介入是正确处置)。示范见 {@code sample-tenant-worker} 的 {@code main}。
   */
  public synchronized void start() {
    if (started) {
      throw new IllegalStateException("client already started");
    }
    if (handlers.isEmpty()) {
      throw new IllegalStateException("at least one SdkTaskHandler must be registered");
    }
    log.info(
        "BatchPlatformClient starting: tenant={}, worker={}, handlers={}",
        config.getTenantId(),
        config.getWorkerCode(),
        handlers.keySet());
    // body 对齐 WorkerHeartbeatDto(tenantId/workerCode/workerGroup/status/heartbeatAt/
    // currentLoad/capabilityTags),taskTypes 走 capabilityTags(平台从此推断 worker 能跑哪些 type)
    Map<String, Object> body = new HashMap<>();
    body.put("tenantId", config.getTenantId());
    body.put("workerCode", config.getWorkerCode());
    body.put("workerGroup", "sdk-self-hosted");
    body.put("status", "RUNNING");
    body.put("heartbeatAt", Instant.now().toString());
    body.put("currentLoad", 0);
    body.put("capabilityTags", List.copyOf(handlers.keySet()));
    // SDK-P5-3 运行指纹:host/pid 尽力采集,buildId 由租户 config 注入,sdkVersion 读 jar manifest;
    // 全部尽力而为(null 字段由 NON_NULL 序列化策略略过,平台列可空)。
    putIfPresent(body, "hostName", WorkerFingerprint.hostName());
    putIfPresent(body, "hostIp", WorkerFingerprint.hostIp());
    putIfPresent(body, "processId", WorkerFingerprint.processId());
    putIfPresent(body, "buildId", config.getBuildId());
    putIfPresent(body, "sdkVersion", WorkerFingerprint.sdkVersion());
    // 协议门禁:声明本 SDK 实现的 wire 协议主版本;平台不支持则 register 被拒(400)。
    body.put("protocolVersion", RegisterRequest.CURRENT_PROTOCOL_VERSION);
    // Phase 3 M3.1:声明了 descriptor 的 handler 随 register 上报 taskTypes[](平台 upsert 到 registry)。
    List<SdkTaskTypeDescriptor> descriptors = collectDescriptors();
    if (!descriptors.isEmpty()) {
      body.put("taskTypes", descriptors);
    }
    try {
      Map<String, Object> resp = httpClient.register(body);
      log.info("BatchPlatformClient registered: response={}", resp);
    } catch (IOException e) {
      throw new BatchSdkClientException(
          BatchSdkClientException.Stage.REGISTER, "worker register failed", e);
    }
    this.dispatcher = new TaskDispatcher(config, handlers, httpClient, idempotencyStore);
    this.kafkaConsumer = new KafkaTaskConsumer(config, dispatcher);
    this.kafkaConsumerThread = new Thread(kafkaConsumer, "batch-sdk-kafka-consumer");
    this.kafkaConsumerThread.setDaemon(false);
    this.kafkaConsumerThread.start();
    // SDK-P5-3 + Python PR #320 对齐:把 register 时的 6 字段身份快照交给 heartbeat 每次带上,
    // 防止 worker_registry 行被运维误删 / 平台冷启重建索引导致 heartbeat 兜底降级 register 时丢字段。
    WorkerIdentity identity =
        new WorkerIdentity(
            "sdk-self-hosted",
            WorkerFingerprint.hostName(),
            WorkerFingerprint.hostIp(),
            WorkerFingerprint.processId(),
            List.copyOf(handlers.keySet()),
            config.getBuildId());
    this.heartbeatScheduler = new HeartbeatScheduler(config, httpClient, dispatcher, identity);
    this.heartbeatScheduler.start();
    this.leaseRenewalScheduler = new LeaseRenewalScheduler(config, httpClient, dispatcher);
    this.leaseRenewalScheduler.start();
    started = true;
  }

  /**
   * 收集声明了 descriptor 的 handler —— code 以 {@link SdkTaskHandler#taskType()} 为权威(覆盖 descriptor 里 可能漏填
   * / 写错的 code),保证 register 上报的 code 与派单路由一致。
   */
  List<SdkTaskTypeDescriptor> collectDescriptors() {
    List<SdkTaskTypeDescriptor> out = new ArrayList<>();
    for (Map.Entry<String, SdkTaskHandler> entry : handlers.entrySet()) {
      SdkTaskTypeDescriptor descriptor = entry.getValue().descriptor();
      if (descriptor != null) {
        out.add(descriptor.withCode(entry.getKey()));
      }
    }
    return out;
  }

  /**
   * 优雅停 — 顺序见 Phase 1 §3.1 #1.1:
   *
   * <ol>
   *   <li>Kafka consumer 先 wakeup + join — 停止接新 dispatch 消息
   *   <li>Dispatcher drain — 等 in-flight handler 跑完(超时 30s),期间 heartbeat / lease 仍在跑 维持租约,避免
   *       orchestrator 在 drain 中误判 worker 死了把任务派给别人
   *   <li>Heartbeat / lease scheduler 关 — drain 完成后才停心跳
   *   <li>Deactivate — 通知平台 OFFLINE(失败 swallow,不阻塞进程退出)
   * </ol>
   */
  public synchronized void stop() {
    stop(Duration.ofSeconds(30));
  }

  /**
   * P0 hardening — 带预算的优雅停。K8s {@code terminationGracePeriodSeconds} 到期前必须返回,否则 SIGKILL 让 in-flight
   * 任务状态错乱。
   *
   * <p>Lane E #5 预算重分配(原 kafka 20% / dispatcher 75% / scheduler+deactivate 5% 精细化):
   *
   * <ul>
   *   <li>Kafka close+join: 15%(显式让 poll thread join 完成 offset commit)
   *   <li>Dispatcher drain: 70%(in-flight handler 主消耗)
   *   <li>Scheduler stop: 10%(lease + heartbeat 两个,各自 5s cap)
   *   <li>Deactivate + 收尾: 剩余 ~5%
   * </ul>
   *
   * 各阶段用 {@link System#nanoTime()} 算剩余时间,超时立刻 forceful 关下一阶段; dispatcher 超时未结束会打 WARN 列出未完成 task
   * id(见 {@link TaskDispatcher#stop(Duration)})。
   *
   * <p>Lane E #4-Java:若 Kafka 因 SASL 凭据错 fatal-failed,跳过 deactivate(凭据已坏,HTTP 也会 401)。
   */
  public synchronized void stop(Duration timeout) {
    if (!started) {
      return;
    }
    long totalMs = Math.max(0L, timeout == null ? 0L : timeout.toMillis());
    long startNanos = System.nanoTime();
    log.info("BatchPlatformClient stopping (timeoutMs={})", totalMs);
    // Lane E #5:Kafka close+join 预算 15%(原 20%);KafkaTaskConsumer.close(Duration) 内部已 join thread。
    long kafkaJoinMs = Math.max(50L, totalMs * 15 / 100);
    if (kafkaConsumer != null) {
      kafkaConsumer.close(Duration.ofMillis(kafkaJoinMs));
    }
    // 兜底再 join 一次(KafkaTaskConsumer.close 已经 join,这里只覆盖极端竞态)
    if (kafkaConsumerThread != null && kafkaConsumerThread.isAlive()) {
      long remainingMs = remainingMs(startNanos, totalMs);
      try {
        kafkaConsumerThread.join(Math.max(50L, Math.min(remainingMs, kafkaJoinMs)));
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
    if (dispatcher != null) {
      long remainingMs = remainingMs(startNanos, totalMs);
      // Lane E #5:dispatcher drain 用剩余预算 - 15%(scheduler+deactivate 保留),目标 ~70% 总预算
      long dispatcherMs = Math.max(0L, remainingMs - totalMs * 15 / 100);
      dispatcher.stop(Duration.ofMillis(dispatcherMs));
    }
    if (heartbeatScheduler != null) {
      heartbeatScheduler.close();
    }
    if (leaseRenewalScheduler != null) {
      leaseRenewalScheduler.close();
    }
    // Lane E #4-Java:凭据 fatal 时,deactivate 也会 401 — 跳过,只 log。
    boolean skipDeactivate = kafkaConsumer != null && kafkaConsumer.isFatalAuthFailure();
    if (skipDeactivate) {
      log.warn(
          "skipping deactivate: Kafka SASL auth failed earlier, "
              + "platform HTTP will also fail with 401");
    } else {
      try {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", config.getTenantId());
        body.put("workerCode", config.getWorkerCode());
        body.put("status", "OFFLINE");
        body.put("heartbeatAt", Instant.now().toString());
        httpClient.deactivate(config.getWorkerCode(), body);
      } catch (Exception ex) {
        log.warn("deactivate call failed (ignored): {}", ex.getMessage());
      }
    }
    started = false;
  }

  private static long remainingMs(long startNanos, long totalMs) {
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
    return Math.max(0L, totalMs - elapsedMs);
  }

  private static void putIfPresent(Map<String, Object> body, String key, String value) {
    if (value != null && !value.isBlank()) {
      body.put(key, value);
    }
  }

  /** 给业务可见的工具方法 — 让自己生成 idempotency-key。 */
  public static String newIdempotencyKey() {
    return "sdk-" + UUID.randomUUID();
  }

  /**
   * Phase 1 §3.1 #1.6:运行时指标快照,供租户对接到自家 Prometheus / Micrometer。 字段说明见 {@link SdkClientMetrics}。
   * 线程安全(全部读 volatile / atomic 状态)。
   */
  public SdkClientMetrics metrics() {
    boolean startedSnap = started;
    boolean fatal = dispatcher != null && dispatcher.isFatal();
    boolean draining = dispatcher != null && dispatcher.isDraining();
    boolean crashed = kafkaConsumer != null && kafkaConsumer.hasCrashed();
    int inFlight = dispatcher == null ? 0 : dispatcher.inFlightCount();
    long consumerLag = kafkaConsumer == null ? -1L : kafkaConsumer.consumerLagMax();
    return new SdkClientMetrics(
        config.getTenantId(),
        config.getWorkerCode(),
        startedSnap,
        startedSnap && !fatal && !crashed,
        inFlight,
        config.getMaxConcurrentTasks(),
        handlers.size(),
        fatal,
        draining,
        crashed,
        consumerLag);
  }

  /**
   * Phase 1 §3.1 #1.6:liveness/readiness 信号。{@code true} = SDK 仍在正常接派单。
   *
   * <ul>
   *   <li>未 start → false(还没 ready)
   *   <li>CLAIM 401/403 之后 dispatcher fatal → false(鉴权失效,运维介入)
   *   <li>Kafka poll loop 因 Throwable 死 → false(参考 #1.7)
   *   <li>drain 中 → true(graceful 状态,lease 仍在续约)
   * </ul>
   */
  public boolean isHealthy() {
    if (!started) return false;
    boolean fatal = dispatcher != null && dispatcher.isFatal();
    boolean crashed = kafkaConsumer != null && kafkaConsumer.hasCrashed();
    return !fatal && !crashed;
  }

  // ─── Builder ────────────────────────────────────────────────────────────────

  public static final class Builder {
    private final BatchPlatformClientConfig config;
    private final Map<String, SdkTaskHandler> handlers = new ConcurrentHashMap<>();
    private SdkIdempotencyStore idempotencyStore;

    private Builder(BatchPlatformClientConfig config) {
      this.config = config;
    }

    /**
     * SDK-P5 auto-wrap:注入声明式幂等所需的去重存储(可选)。注册了标 {@code @Idempotent} 的 handler 时必须设置,否则 {@link
     * TaskDispatcher} 构造期 fail-fast。无幂等 handler 时无需调用。
     */
    public Builder idempotencyStore(SdkIdempotencyStore store) {
      this.idempotencyStore = store;
      return this;
    }

    public Builder register(SdkTaskHandler handler) {
      String type = handler.taskType();
      if (type == null || type.isBlank()) {
        throw new IllegalArgumentException("handler.taskType() must be non-blank");
      }
      SdkTaskHandler prev = handlers.putIfAbsent(type, handler);
      if (prev != null) {
        throw new IllegalStateException("duplicate taskType registered: " + type);
      }
      return this;
    }

    public BatchPlatformClient build() {
      return new BatchPlatformClient(config, handlers, idempotencyStore);
    }
  }
}
