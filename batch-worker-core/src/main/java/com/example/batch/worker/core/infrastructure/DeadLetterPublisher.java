package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.utils.JsonUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * D-3: 死信队列发布器，将处理失败的消息转发到 {@link BatchTopics#TASK_DEAD_LETTER}， 防止毒丸消息阻塞主派发队列。运维可从 DLQ 查看并重放。
 *
 * <p>P0-3: 之前用 {@code future.join()} 无限阻塞 listener 线程, broker 抖动会让 consumer 吃满 {@code
 * max.poll.interval.ms} → rebalance → 大批任务重派. 改为限时阻塞 (默认 5s), 超时即 throw, 调用方 (AbstractTaskConsumer)
 * 据此决定不 ack 触发 Kafka redeliver, 同时 metric 暴露超时/失败计数供告警.
 */
@Slf4j
@Component
public class DeadLetterPublisher {

  /** DLQ 发送限时. 超出即 throw, 让消息留在 source topic 由 Kafka 自动 redeliver. */
  static final long PUBLISH_TIMEOUT_SECONDS = 5L;

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final Counter successCounter;
  private final Counter timeoutCounter;
  private final Counter failureCounter;
  private final Timer publishTimer;

  public DeadLetterPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.kafkaTemplate = kafkaTemplate;
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (registry == null) {
      this.successCounter = null;
      this.timeoutCounter = null;
      this.failureCounter = null;
      this.publishTimer = null;
    } else {
      Tags base = Tags.of("topic", BatchTopics.TASK_DEAD_LETTER);
      this.successCounter =
          Counter.builder("worker.dlq.publish.success.total")
              .description("DLQ 发布成功累计")
              .tags(base)
              .register(registry);
      this.timeoutCounter =
          Counter.builder("worker.dlq.publish.timeout.total")
              .description("DLQ 发布超时累计 (broker 抖动 / 容量不足时上升)")
              .tags(base)
              .register(registry);
      this.failureCounter =
          Counter.builder("worker.dlq.publish.failed.total")
              .description("DLQ 发布失败累计 (超时之外的异常)")
              .tags(base)
              .register(registry);
      this.publishTimer =
          Timer.builder("worker.dlq.publish.duration")
              .description("DLQ 发布耗时分位")
              .tags(base)
              .publishPercentiles(0.5, 0.95, 0.99)
              .register(registry);
    }
  }

  /**
   * 将失败消息发布到死信 topic.
   *
   * <p>语义: 限时同步 (默认 {@value #PUBLISH_TIMEOUT_SECONDS}s); 超时 / IO 异常即抛, 让上游不提交 source offset, Kafka
   * 自动 redeliver. 不再无限阻塞 listener 线程.
   */
  public void publish(
      String originalPayload, String sourceTopic, String workerType, String errorMessage) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("originalPayload", originalPayload);
    envelope.put("sourceTopic", sourceTopic);
    envelope.put("workerType", workerType);
    envelope.put("errorMessage", truncate(errorMessage, 2000));
    envelope.put("failedAt", Instant.now().toString());
    String value = JsonUtils.toJson(envelope);

    long startNanos = System.nanoTime();
    try {
      CompletableFuture<?> future = kafkaTemplate.send(BatchTopics.TASK_DEAD_LETTER, value);
      if (future != null) {
        future.get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
      if (successCounter != null) {
        successCounter.increment();
      }
      log.info("published to DLQ: sourceTopic={}, workerType={}", sourceTopic, workerType);
    } catch (TimeoutException ex) {
      if (timeoutCounter != null) {
        timeoutCounter.increment();
      }
      log.error(
          "DLQ publish timeout after {}s: sourceTopic={}, workerType={}",
          PUBLISH_TIMEOUT_SECONDS,
          sourceTopic,
          workerType);
      throw new IllegalStateException(
          "dead letter publish timeout after " + PUBLISH_TIMEOUT_SECONDS + "s", ex);
    } catch (ExecutionException ex) {
      if (failureCounter != null) {
        failureCounter.increment();
      }
      log.error(
          "DLQ publish failed: sourceTopic={}, workerType={}, error={}",
          sourceTopic,
          workerType,
          ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage());
      throw new IllegalStateException("dead letter publish failed", ex.getCause());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      if (failureCounter != null) {
        failureCounter.increment();
      }
      throw new IllegalStateException("dead letter publish interrupted", ex);
    } finally {
      if (publishTimer != null) {
        publishTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
      }
    }
  }

  private static String truncate(String value, int maxLength) {
    if (value == null) {
      return null;
    }
    return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
  }
}
