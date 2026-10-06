package io.github.pinpols.batch.trigger.infrastructure.kafka;

import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.application.TriggerLaunchLagQueryPort;
import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * {@link TriggerLaunchLagQueryPort} 的 Kafka Admin 适配器：复用一个受 Spring 生命周期管理的 AdminClient，
 * 只读 offset 计算 consumer group 积压。
 */
@Component
@RequiredArgsConstructor
public class KafkaTriggerLaunchLagQueryAdapter implements TriggerLaunchLagQueryPort {

  private final KafkaAdmin kafkaAdmin;
  private final TriggerOutboxRelayProperties properties;
  private final Object adminClientMonitor = new Object();
  private final AtomicBoolean stopping = new AtomicBoolean();
  private AdminClient adminClient;

  @Override
  public long sampleLag() throws InterruptedException, ExecutionException, TimeoutException {
    long timeout = properties.getLagQueryTimeoutMillis();
    AdminClient admin = adminClient();
    Map<TopicPartition, OffsetAndMetadata> committed = admin
        .listConsumerGroupOffsets(properties.getConsumerGroupId())
        .partitionsToOffsetAndMetadata()
        .get(timeout, TimeUnit.MILLISECONDS);
    TopicDescription topic = admin
        .describeTopics(List.of(BatchTopics.TRIGGER_LAUNCH_V1))
        .allTopicNames()
        .get(timeout, TimeUnit.MILLISECONDS)
        .get(BatchTopics.TRIGGER_LAUNCH_V1);
    if (EmptyChecks.isNull(topic)) {
      return UNKNOWN_LAG;
    }
    Map<TopicPartition, OffsetSpec> requests = new LinkedHashMap<>();
    topic
        .partitions()
        .forEach(partition -> requests.put(
            new TopicPartition(BatchTopics.TRIGGER_LAUNCH_V1, partition.partition()),
            OffsetSpec.latest()));
    Map<TopicPartition, ListOffsetsResultInfo> endOffsets =
        admin.listOffsets(requests).all().get(timeout, TimeUnit.MILLISECONDS);
    Map<TopicPartition, OffsetSpec> earliestRequests = new LinkedHashMap<>();
    requests.keySet().stream()
        .filter(partition -> !committed.containsKey(partition))
        .forEach(partition -> earliestRequests.put(partition, OffsetSpec.earliest()));
    Map<TopicPartition, ListOffsetsResultInfo> earliestOffsets =
        EmptyChecks.isEmpty(earliestRequests)
            ? Map.<TopicPartition, ListOffsetsResultInfo>of()
            : admin.listOffsets(earliestRequests).all().get(timeout, TimeUnit.MILLISECONDS);
    long lag = 0L;
    for (Map.Entry<TopicPartition, ListOffsetsResultInfo> entry : endOffsets.entrySet()) {
      OffsetAndMetadata offset = committed.get(entry.getKey());
      long committedOffset = EmptyChecks.isNull(offset)
          ? earliestOffsets.get(entry.getKey()).offset()
          : offset.offset();
      lag = Math.addExact(lag, Math.max(0L, entry.getValue().offset() - committedOffset));
    }
    return lag;
  }

  private AdminClient adminClient() {
    synchronized (adminClientMonitor) {
      if (stopping.get()) {
        throw new IllegalStateException("Trigger launch lag query adapter is stopping");
      }
      if (EmptyChecks.isNull(adminClient)) {
        adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties());
      }
      return adminClient;
    }
  }

  @PreDestroy
  void closeAdminClient() {
    stopping.set(true);
    synchronized (adminClientMonitor) {
      if (EmptyChecks.isNotNull(adminClient)) {
        adminClient.close(Duration.ZERO);
        adminClient = null;
      }
    }
  }
}
