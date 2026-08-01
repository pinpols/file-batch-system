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
              + "ms) must be <= session.timeout.ms / 3 ("
              + (sessionTimeoutMs / 3)
              + "ms). Kafka protocol constraint: a long heartbeat interval can make the broker treat the consumer as disconnected and trigger a rebalance.");
    }
    if (sessionTimeoutMs >= maxPollIntervalMs) {
      throw new IllegalStateException("FATAL: spring.kafka.consumer.properties.session.timeout.ms ("
          + sessionTimeoutMs
          + "ms) must be < max.poll.interval.ms ("
          + maxPollIntervalMs
          + "ms). A session timeout before max-poll triggers can break rebalance semantics.");
    }
    if (!partitionAssignmentStrategy.contains("CooperativeStickyAssignor")) {
      throw new IllegalStateException(
          "FATAL: spring.kafka.consumer.properties.partition.assignment.strategy is currently ["
              + partitionAssignmentStrategy
              + "]; it must include CooperativeStickyAssignor."
              + " The default RangeAssignor triggers a stop-the-world rebalance during worker rolling updates,"
              + " pausing all partitions. See docs/runbook/kafka-consumer-rolling-upgrade.md.");
    }
    log.info(
        "Kafka consumer triangle validation passed: session.timeout={}ms heartbeat.interval={}ms max.poll.interval={}ms"
            + " partition.assignment.strategy={}",
        sessionTimeoutMs,
        heartbeatIntervalMs,
        maxPollIntervalMs,
        partitionAssignmentStrategy);
  }
}
