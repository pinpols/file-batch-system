package com.example.batch.sdk.client;

import com.example.batch.sdk.dispatcher.KafkaTaskConsumer;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.task.SdkTaskHandler;
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
    Map<String, Object> body = new HashMap<>();
    body.put("tenantId", config.getTenantId());
    body.put("workerCode", config.getWorkerCode());
    body.put("taskTypes", List.copyOf(handlers.keySet()));
    body.put("maxConcurrentTasks", config.getMaxConcurrentTasks());
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
    started = true;
    // TODO 后续 PR:Heartbeat scheduler + lease renewal
  }

  /** 优雅停 — 反注册 + 关 Kafka consumer + 等当前任务完成。 */
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
