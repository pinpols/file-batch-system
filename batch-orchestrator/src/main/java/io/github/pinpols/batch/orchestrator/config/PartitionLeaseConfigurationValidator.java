package io.github.pinpols.batch.orchestrator.config;

import io.github.pinpols.batch.common.config.KafkaConsumerTimingProperties;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 校验分片租约窗口与 Kafka 消费者轮询窗口的关系。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionLeaseConfigurationValidator {

  private static final int DEFAULT_MAX_POLL_INTERVAL_MILLIS = 600_000;

  private final PartitionLeaseProperties partitionLeaseProperties;
  private final KafkaConsumerTimingProperties kafkaConsumerTimingProperties;

  @PostConstruct
  void validate() {
    long maxPollSeconds = maxPollIntervalMillis() / 1000L;
    long expireSeconds = partitionLeaseProperties.getExpireSeconds();
    if (expireSeconds >= maxPollSeconds) {
      throw new IllegalStateException("FATAL: batch.partition-lease.expire-seconds ("
          + expireSeconds
          + "s) must be lower than spring.kafka.consumer.properties.max-poll-interval-ms ("
          + maxPollSeconds
          + "s). Rebalance and lease expiry in the same window can cause duplicate execution.");
    }
    if (expireSeconds >= maxPollSeconds / 2) {
      log.warn(
          "batch.partition-lease.expire-seconds ({}s) is close to half of max-poll-interval ({}s);"
              + " lease overlap risk increases during rebalance; reduce it below one third.",
          expireSeconds,
          maxPollSeconds);
    }
  }

  private int maxPollIntervalMillis() {
    Map<String, String> properties = kafkaConsumerTimingProperties.getProperties();
    String value = properties.get("max-poll-interval-ms");
    return value == null
        ? DEFAULT_MAX_POLL_INTERVAL_MILLIS
        : Integer.parseInt(value);
  }
}
