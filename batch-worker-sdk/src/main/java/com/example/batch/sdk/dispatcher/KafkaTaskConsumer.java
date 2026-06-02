package com.example.batch.sdk.dispatcher;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Kafka task dispatch topic consumer — 单线程 poll loop,把消息反序列化为 {@link TaskDispatchMessage} 后扔给
 * {@link TaskDispatcher}。
 *
 * <p>关键约束:
 *
 * <ul>
 *   <li>**手动 commit**(enable.auto.commit=false):dispatcher 提交到线程池后立刻 commit offset,避免重启时重发 (重发由平台
 *       lease 超时 + idempotency-key 兜底,SDK 这里不重复消费)
 *   <li>{@link BatchPlatformClientConfig#getKafkaTopicPattern()} 支持
 *       wildcard(`batch.task.dispatch.<tenant>.*`)
 *   <li>JSON 反序列化失败 → log ERROR + skip,不死循环
 * </ul>
 */
@Slf4j
public class KafkaTaskConsumer implements Runnable, AutoCloseable {

  private final BatchPlatformClientConfig config;
  private final TaskDispatcher dispatcher;
  private final ObjectMapper objectMapper;
  private final Consumer<String, byte[]> consumer;
  private final AtomicBoolean running = new AtomicBoolean(true);

  /**
   * P1-4 #1.7:poll loop 因非预期 Throwable 退出时置 true。 与 {@link #running}=false 区分:running=false 可能是正常
   * {@link #close()},crashed=true 表示无法继续消费,{@link
   * com.example.batch.sdk.client.BatchPlatformClient#isHealthy()} 据此报 false 让 K8s liveness probe
   * 重启进程。
   */
  private final AtomicBoolean crashed = new AtomicBoolean(false);

  /** P0 hardening:in-flight 达上限时 pause 当前 partition;掉下来再 resume。Zeebe maxJobsActive 模式。 */
  private volatile boolean paused = false;

  /**
   * P7-1:最近一次 poll 后读到的 Kafka {@code records-lag-max}(所有 assigned partition 的最大滞后条数)。 {@code -1}
   * 表示尚未知(未 poll 过 / consumer 未暴露该指标)。只在 poll 线程写,{@code volatile} 供 {@link #consumerLagMax()} 跨线程读
   * —— 避免在 metrics 线程直接碰非线程安全的 {@link Consumer}。
   */
  private volatile long consumerLagMax = -1L;

  public KafkaTaskConsumer(BatchPlatformClientConfig config, TaskDispatcher dispatcher) {
    this(
        config,
        dispatcher,
        defaultConsumer(config),
        new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
  }

  /** test-friendly ctor:可注入 mock Consumer(含 {@code MockConsumer})。 */
  KafkaTaskConsumer(
      BatchPlatformClientConfig config,
      TaskDispatcher dispatcher,
      Consumer<String, byte[]> consumer,
      ObjectMapper objectMapper) {
    this.config = config;
    this.dispatcher = dispatcher;
    this.consumer = consumer;
    this.objectMapper = objectMapper;
  }

  private static KafkaConsumer<String, byte[]> defaultConsumer(BatchPlatformClientConfig config) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrap());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, config.getKafkaGroupId());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    // P3:per-tenant Kafka SASL/SCRAM(ACL 路径)。三个字段都置空 → 走 PLAINTEXT(本地联调);
    // 任一非空 → 全部按设值传给 Kafka client(prod 必填 protocol + mechanism + jaasConfig)。
    if (notBlank(config.getKafkaSecurityProtocol())) {
      props.put("security.protocol", config.getKafkaSecurityProtocol());
    }
    if (notBlank(config.getKafkaSaslMechanism())) {
      props.put("sasl.mechanism", config.getKafkaSaslMechanism());
    }
    if (notBlank(config.getKafkaSaslJaasConfig())) {
      props.put("sasl.jaas.config", config.getKafkaSaslJaasConfig());
    }
    return new KafkaConsumer<>(props);
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  /** 启动 poll loop。阻塞当前线程,通常在专用线程跑。 */
  @Override
  public void run() {
    consumer.subscribe(
        Pattern.compile(config.getKafkaTopicPattern()), new PauseAwareRebalanceListener());
    log.info(
        "KafkaTaskConsumer started: tenant={}, topicPattern={}, group={}",
        config.getTenantId(),
        config.getKafkaTopicPattern(),
        config.getKafkaGroupId());

    try {
      while (running.get()) {
        applyBackpressure();
        ConsumerRecords<String, byte[]> records = consumer.poll(config.getKafkaPollInterval());
        refreshConsumerLag();
        if (records.isEmpty()) continue;
        for (ConsumerRecord<String, byte[]> rec : records) {
          handleRecord(rec);
        }
        // 同步 commit,确保 dispatcher submit 已成功
        try {
          consumer.commitSync();
        } catch (Exception ex) {
          log.warn("kafka commitSync failed (will retry next poll): {}", ex.getMessage());
        }
      }
    } catch (org.apache.kafka.common.errors.WakeupException wakeup) {
      // 正常 stop 触发
    } catch (Throwable t) {
      // P1-4 #1.7:非预期退出 — 置 crashed + running=false,让 BatchPlatformClient.isHealthy() 报 false,
      // 不静默死。K8s liveness probe / 运维监控由此感知到 worker 实质已停消费。
      crashed.set(true);
      running.set(false);
      log.error("KafkaTaskConsumer poll loop died (marked crashed)", t);
    } finally {
      try {
        consumer.close();
      } catch (Exception e) {
        log.warn("kafka consumer close error: {}", e.getMessage());
      }
      log.info("KafkaTaskConsumer stopped");
    }
  }

  /**
   * P0 hardening(borrowed from Zeebe maxJobsActive):in-flight 已满则 pause assigned partitions,
   * 队列降到一半以下再 resume。注意 pause/resume 是按 partition 维度,**不停 poll loop**(否则 consumer heartbeat 也会停 →
   * consumer group rebalance 把当前 worker 踢)。
   *
   * <p>Round-3 #1(ADR-035 §11.1 / Round-2 P0 #1 闭环):resume 阈值带 hysteresis —— pause 在 {@code
   * inFlight >= max},resume 只有当 {@code inFlight < max * 0.5}(整数除法即 {@code max / 2}) 时才触发。 上下边界拉开,避免
   * in-flight 在 max-1 / max 间快速抖动时 pause/resume 反复颠簸 Kafka client(每次 resume 都触发一次新
   * fetch,频繁切换会浪费带宽且让 records-lag-max 失真)。Zeebe maxJobsActive 默认 activation threshold 也是这个模式。
   *
   * <p>Phase 2 §2.4:平台 PAUSED / DRAINING(见 {@link TaskDispatcher#platformAcceptsNewTasks()})也触发同样的
   * partition pause —— 用 pause 而非 consume-and-drop,offset 不前进,平台恢复 NORMAL 后从原位继续消费,不丢任务。 平台层 resume
   * 不受 hysteresis 影响:只要平台再次 accept,立即 resume(不存在抖动来源)。
   */
  void applyBackpressure() {
    int max = config.getMaxConcurrentTasks();
    int inFlight = dispatcher.inFlightCount();
    boolean platformPaused = !dispatcher.platformAcceptsNewTasks();
    // 容量维度 pause:inFlight 达到上限;resume:inFlight 跌破 max/2(hysteresis 防抖)
    boolean capacityPause = inFlight >= max;
    boolean capacityResumeOk = inFlight < Math.max(1, max / 2);
    if (capacityPause || platformPaused) {
      if (!paused && !consumer.assignment().isEmpty()) {
        consumer.pause(consumer.assignment());
        paused = true;
        log.info(
            "consumer pause: inFlight={} max={} platformState={}",
            inFlight,
            max,
            dispatcher.platformState());
      }
    } else if (paused && !platformPaused && capacityResumeOk) {
      if (!consumer.assignment().isEmpty()) {
        consumer.resume(consumer.assignment());
        paused = false;
        log.info(
            "consumer resume: inFlight={} max={} platformState={} (below {}*0.5 hysteresis)",
            inFlight,
            max,
            dispatcher.platformState(),
            max);
      }
    }
  }

  /**
   * P7-1:在 poll 线程读 Kafka client 自带的 {@code records-lag-max} 指标(consumer-fetch-manager-metrics
   * group)写入 {@link #consumerLagMax}。只在 poll 线程触碰 {@link Consumer},满足其单线程约束;指标缺失(如刚启动 / mock
   * consumer)时保持上一次值不动。
   */
  private void refreshConsumerLag() {
    try {
      for (Map.Entry<MetricName, ? extends Metric> e : consumer.metrics().entrySet()) {
        if ("records-lag-max".equals(e.getKey().name())) {
          Object value = e.getValue().metricValue();
          if (value instanceof Number n) {
            double d = n.doubleValue();
            // Kafka 无数据时给 NaN / -Inf,只在拿到有限非负值时更新
            if (Double.isFinite(d) && d >= 0) {
              consumerLagMax = (long) d;
            }
          }
          return;
        }
      }
    } catch (RuntimeException ex) {
      log.debug("refreshConsumerLag skipped: {}", ex.getMessage());
    }
  }

  private void handleRecord(ConsumerRecord<String, byte[]> rec) {
    if (rec.value() == null || rec.value().length == 0) {
      log.warn("empty kafka message at topic={}, offset={}, skipping", rec.topic(), rec.offset());
      return;
    }
    TaskDispatchMessage msg;
    try {
      msg = objectMapper.readValue(rec.value(), TaskDispatchMessage.class);
    } catch (Exception ex) {
      log.error(
          "failed to parse kafka task dispatch message at topic={}, offset={}: {}",
          rec.topic(),
          rec.offset(),
          ex.getMessage());
      return;
    }
    // Phase 0 §2.1:reject 未知 major schema(避免老 SDK 误解平台新 v3 消息)
    if (!msg.isSchemaSupported()) {
      log.warn(
          "rejecting kafka task dispatch message with unsupported schemaVersion={} at topic={},"
              + " offset={}, taskId={}; upgrade SDK",
          msg.schemaVersion(),
          rec.topic(),
          rec.offset(),
          msg.taskId());
      return;
    }
    dispatcher.onMessage(msg);
  }

  /** 让 poll loop 退出。WakeupException 在 run() 被捕获 → close consumer。 */
  @Override
  public void close() {
    if (running.compareAndSet(true, false)) {
      log.info("KafkaTaskConsumer wakeup requested");
      consumer.wakeup();
    }
  }

  /** 暴露给测试:当前 running 状态。 */
  boolean isRunning() {
    return running.get();
  }

  /**
   * P1-4 #1.7:poll loop 是否因非预期 Throwable 退出。正常 {@link #close()} 不会让此返回 true。 供 {@link
   * com.example.batch.sdk.client.BatchPlatformClient#isHealthy()} 判定。
   */
  public boolean hasCrashed() {
    return crashed.get();
  }

  /**
   * P7-1:最近一次 poll 观测到的最大 consumer lag(条数),{@code -1} = 未知。供 {@link
   * com.example.batch.sdk.client.BatchPlatformClient#metrics()} 透出给租户监控。
   */
  public long consumerLagMax() {
    return consumerLagMax;
  }

  /** 给 BatchPlatformClient 注入额外 properties(实际项目会扩展 SASL / SSL 等)。 */
  public static Properties augmentSecurityProperties(
      BatchPlatformClientConfig config, Properties extra) {
    Map<String, Object> merged = new HashMap<>();
    for (String k : extra.stringPropertyNames()) {
      merged.put(k, extra.getProperty(k));
    }
    Properties out = new Properties();
    out.putAll(merged);
    return out;
  }

  /**
   * Phase 1 §3.1 #1.3:rebalance 后 Kafka 不保留 partition 级别的 pause 状态, 新分到的 partition 默认 RESUMED。如果
   * backpressure 期间发生 rebalance, 必须在 {@code onPartitionsAssigned} 立刻重新 pause 新拿到的 partition, 否则
   * inFlight 已满时也会拉新消息,绕过 maxConcurrent 上限。
   */
  final class PauseAwareRebalanceListener implements ConsumerRebalanceListener {
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
      log.info("kafka partitions revoked: {}", partitions);
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
      log.info("kafka partitions assigned: {}", partitions);
      if (paused && !partitions.isEmpty()) {
        consumer.pause(partitions);
        log.info(
            "re-paused {} newly assigned partition(s) after rebalance (backpressure still active)",
            partitions.size());
      }
    }
  }
}
