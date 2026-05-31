package com.example.batch.sdk.dispatcher;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
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
  private final KafkaConsumer<String, byte[]> consumer;
  private final AtomicBoolean running = new AtomicBoolean(true);

  public KafkaTaskConsumer(BatchPlatformClientConfig config, TaskDispatcher dispatcher) {
    this(
        config,
        dispatcher,
        defaultConsumer(config),
        new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
  }

  /** test-friendly ctor:可注入 mock KafkaConsumer。 */
  KafkaTaskConsumer(
      BatchPlatformClientConfig config,
      TaskDispatcher dispatcher,
      KafkaConsumer<String, byte[]> consumer,
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
    // SASL(P3 启用 ACL 后):
    // props.put("security.protocol", "SASL_SSL");
    // props.put("sasl.mechanism", "SCRAM-SHA-512");
    return new KafkaConsumer<>(props);
  }

  /** 启动 poll loop。阻塞当前线程,通常在专用线程跑。 */
  @Override
  public void run() {
    consumer.subscribe(Pattern.compile(config.getKafkaTopicPattern()));
    log.info(
        "KafkaTaskConsumer started: tenant={}, topicPattern={}, group={}",
        config.getTenantId(),
        config.getKafkaTopicPattern(),
        config.getKafkaGroupId());

    try {
      while (running.get()) {
        ConsumerRecords<String, byte[]> records = consumer.poll(config.getKafkaPollInterval());
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
      log.error("KafkaTaskConsumer poll loop died", t);
    } finally {
      try {
        consumer.close();
      } catch (Exception e) {
        log.warn("kafka consumer close error: {}", e.getMessage());
      }
      log.info("KafkaTaskConsumer stopped");
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
}
