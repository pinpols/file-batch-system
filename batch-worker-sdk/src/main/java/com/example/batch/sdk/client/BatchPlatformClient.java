package com.example.batch.sdk.client;

import com.example.batch.sdk.dispatcher.KafkaTaskConsumer;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.scheduler.HeartbeatScheduler;
import com.example.batch.sdk.scheduler.LeaseRenewalScheduler;
import com.example.batch.sdk.task.SdkTaskHandler;
import java.time.Instant;
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

  /** 启动 — 调 register API + (后续 PR) 启动 Kafka consumer + heartbeat scheduler。 */
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
    try {
      Map<String, Object> resp = httpClient.register(body);
      log.info("BatchPlatformClient registered: response={}", resp);
    } catch (java.io.IOException e) {
      throw new RuntimeException("worker register failed", e);
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

  /** 给业务可见的工具方法 — 让自己生成 idempotency-key。 */
  public static String newIdempotencyKey() {
    return "sdk-" + UUID.randomUUID();
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
