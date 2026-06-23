package io.github.pinpols.batch.common.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * P1-10 启动期 fail-fast 校验 Kafka consumer 三角时序 + 分配策略。
 *
 * <p>三角约束:
 *
 * <ul>
 *   <li>{@code heartbeat.interval.ms} 必须 &lt; {@code session.timeout.ms / 3} (Kafka 协议硬约束)
 *   <li>{@code session.timeout.ms} 必须 &lt; {@code max.poll.interval.ms} (rebalance 语义)
 *   <li>{@code partition.assignment.strategy} 必须为 CooperativeStickyAssignor (避免 stop-the-world
 *       rebalance)
 * </ul>
 *
 * <p>本校验只在显式开启 {@code spring.kafka.bootstrap-servers} 的模块生效;不消费 Kafka 的模块(如 console-api / trigger)
 * 由 {@link ConditionalOnProperty} matchIfMissing=false 跳过。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(name = "org.apache.kafka.clients.consumer.ConsumerConfig")
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class KafkaConsumerTriangleValidator {

  @Value("${spring.kafka.consumer.properties.session.timeout.ms:45000}")
  private int sessionTimeoutMs;

  @Value("${spring.kafka.consumer.properties.heartbeat.interval.ms:15000}")
  private int heartbeatIntervalMs;

  @Value(
      "${spring.kafka.consumer.max-poll-interval-ms:${spring.kafka.consumer.properties.max.poll.interval.ms:300000}}")
  private int maxPollIntervalMs;

  @Value(
      "${spring.kafka.consumer.properties.partition.assignment.strategy:org.apache.kafka.clients.consumer.CooperativeStickyAssignor}")
  private String partitionAssignmentStrategy;

  @PostConstruct
  void validate() {
    // Kafka 推荐 heartbeat 约 session.timeout/3,等号成立(精确 1/3)是推荐配置,允许通过;
    // 严格大于 session.timeout/3 才是违约(broker 来不及收到 3 次心跳即判失联)。
    if (heartbeatIntervalMs > sessionTimeoutMs / 3) {
      throw new IllegalStateException(
          "FATAL: spring.kafka.consumer.properties.heartbeat.interval.ms ("
              + heartbeatIntervalMs
              + "ms) 必须 ≤ session.timeout.ms / 3 ("
              + (sessionTimeoutMs / 3)
              + "ms)。Kafka 协议硬约束: 心跳间隔过长会被 broker 误判为失联触发 rebalance。");
    }
    if (sessionTimeoutMs >= maxPollIntervalMs) {
      throw new IllegalStateException(
          "FATAL: spring.kafka.consumer.properties.session.timeout.ms ("
              + sessionTimeoutMs
              + "ms) 必须 < max.poll.interval.ms ("
              + maxPollIntervalMs
              + "ms)。session.timeout 早于 max-poll 触发会破坏 rebalance 语义。");
    }
    if (!partitionAssignmentStrategy.contains("CooperativeStickyAssignor")) {
      throw new IllegalStateException(
          "FATAL: spring.kafka.consumer.properties.partition.assignment.strategy 当前 ["
              + partitionAssignmentStrategy
              + "],必须包含 CooperativeStickyAssignor。"
              + " 默认 RangeAssignor 会在 worker rolling update 时触发 stop-the-world rebalance,"
              + " partition 全暂停。详见 docs/runbook/kafka-consumer-rolling-upgrade.md。");
    }
    log.info(
        "Kafka consumer 三角校验通过: session.timeout={}ms heartbeat.interval={}ms max.poll.interval={}ms"
            + " partition.assignment.strategy={}",
        sessionTimeoutMs,
        heartbeatIntervalMs,
        maxPollIntervalMs,
        partitionAssignmentStrategy);
  }
}
