package io.github.pinpols.batch.trigger.application;

import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * 独立采样 trigger launch consumer group lag，为 Relay 自适应释放提供集群级下游压力信号。
 *
 * <p>采样只读 Kafka offset，不消费消息、不提交 offset。查询失败时发布未知样本，由发布治理器收缩到最小速率；
 * 采样在专用调度线程执行，不阻塞 outbox DB/Kafka 发布线程。
 */
@Component
@Slf4j
public class TriggerLaunchLagMonitor {

  static final long UNKNOWN_LAG = -1L;

  private final KafkaAdmin kafkaAdmin;
  private final TriggerOutboxRelayProperties properties;
  private final ThreadPoolTaskScheduler scheduler;
  private final AtomicReference<LagSnapshot> snapshot =
      new AtomicReference<>(new LagSnapshot(UNKNOWN_LAG, 0L));
  private final AtomicLong sequence = new AtomicLong();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean stopping = new AtomicBoolean();
  private final AtomicReference<ScheduledFuture<?>> scheduledTask = new AtomicReference<>();
  private final Object adminClientMonitor = new Object();
  private AdminClient adminClient;

  public TriggerLaunchLagMonitor(
      KafkaAdmin kafkaAdmin,
      TriggerOutboxRelayProperties properties,
      MeterRegistry meterRegistry,
      @Qualifier("triggerLaunchLagMonitorScheduler") ThreadPoolTaskScheduler scheduler) {
    this.kafkaAdmin = kafkaAdmin;
    this.properties = properties;
    this.scheduler = scheduler;
    meterRegistry.gauge(
        "batch.trigger.launch.consumer.lag", snapshot, value -> value.get().lag());
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (!properties.isAdaptiveReleaseEnabled() || !started.compareAndSet(false, true)) {
      return;
    }
    scheduledTask.set(scheduler.scheduleWithFixedDelay(
        this::sampleSafely, Duration.ofMillis(properties.getLagSampleIntervalMillis())));
  }

  @EventListener(ContextClosedEvent.class)
  public void stop() {
    stopping.set(true);
    ScheduledFuture<?> task = scheduledTask.getAndSet(null);
    if (task != null) {
      task.cancel(true);
    }
    closeAdminClient();
  }

  LagSnapshot current() {
    return snapshot.get();
  }

  void sampleSafely() {
    if (stopping.get()) {
      return;
    }
    try {
      publishSnapshot(queryLag());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      if (!stopping.get()) {
        publishSnapshot(UNKNOWN_LAG);
      }
    } catch (ExecutionException | TimeoutException | RuntimeException exception) {
      if (!stopping.get()) {
        publishSnapshot(UNKNOWN_LAG);
        log.warn("Failed to sample trigger launch consumer lag: {}", exception.getMessage());
      }
    }
  }

  private long queryLag() throws InterruptedException, ExecutionException, TimeoutException {
    long timeout = properties.getLagQueryTimeoutMillis();
    AdminClient admin = getOrCreateAdminClient();
    Map<TopicPartition, OffsetAndMetadata> committed = admin
        .listConsumerGroupOffsets(properties.getConsumerGroupId())
        .partitionsToOffsetAndMetadata()
        .get(timeout, TimeUnit.MILLISECONDS);
    TopicDescription topic = admin
        .describeTopics(List.of(BatchTopics.TRIGGER_LAUNCH_V1))
        .allTopicNames()
        .get(timeout, TimeUnit.MILLISECONDS)
        .get(BatchTopics.TRIGGER_LAUNCH_V1);
    if (topic == null) {
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
      long committedOffset =
          offset == null ? earliestOffsets.get(entry.getKey()).offset() : offset.offset();
      lag = Math.addExact(lag, Math.max(0L, entry.getValue().offset() - committedOffset));
    }
    return lag;
  }

  private AdminClient getOrCreateAdminClient() {
    synchronized (adminClientMonitor) {
      if (stopping.get()) {
        throw new IllegalStateException("Trigger launch lag monitor is stopping");
      }
      if (adminClient == null) {
        adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties());
      }
      return adminClient;
    }
  }

  private void closeAdminClient() {
    synchronized (adminClientMonitor) {
      if (adminClient != null) {
        adminClient.close(Duration.ZERO);
        adminClient = null;
      }
    }
  }

  private void publishSnapshot(long lag) {
    snapshot.set(new LagSnapshot(lag, sequence.incrementAndGet()));
  }

  record LagSnapshot(long lag, long sequence) {}
}
