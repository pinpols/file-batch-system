package com.example.batch.sdk.client;

import com.example.batch.sdk.dispatcher.KafkaTaskConsumer;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.scheduler.HeartbeatScheduler;
import com.example.batch.sdk.scheduler.LeaseRenewalScheduler;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskTypeDescriptor;
import java.io.IOException;
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
  private volatile boolean started = false;
  private TaskDispatcher dispatcher;
  private KafkaTaskConsumer kafkaConsumer;
  private Thread kafkaConsumerThread;
  private HeartbeatScheduler heartbeatScheduler;
  private LeaseRenewalScheduler leaseRenewalScheduler;

  private BatchPlatformClient(
      BatchPlatformClientConfig config, Map<String, SdkTaskHandler> handlers) {
    config.validate();
    this.config = config;
    this.handlers = Map.copyOf(handlers);
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
    this.dispatcher = new TaskDispatcher(config, handlers, httpClient);
    this.kafkaConsumer = new KafkaTaskConsumer(config, dispatcher);
    this.kafkaConsumerThread = new Thread(kafkaConsumer, "batch-sdk-kafka-consumer");
    this.kafkaConsumerThread.setDaemon(false);
    this.kafkaConsumerThread.start();
    this.heartbeatScheduler = new HeartbeatScheduler(config, httpClient, dispatcher);
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
    if (!started) {
      return;
    }
    log.info("BatchPlatformClient stopping");
    if (kafkaConsumer != null) {
      kafkaConsumer.close();
    }
    if (kafkaConsumerThread != null) {
      try {
        kafkaConsumerThread.join(10_000);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
    if (dispatcher != null) {
      dispatcher.stop();
    }
    if (heartbeatScheduler != null) {
      heartbeatScheduler.close();
    }
    if (leaseRenewalScheduler != null) {
      leaseRenewalScheduler.close();
    }
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
    started = false;
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

    private Builder(BatchPlatformClientConfig config) {
      this.config = config;
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
      return new BatchPlatformClient(config, handlers);
    }
  }
}
