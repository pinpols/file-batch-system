package io.github.pinpols.batch.console.application.ops.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.ops.ConsoleKafkaLagQueryPort;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleKafkaConsumerLagResponse;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

/** Kafka consumer group lag 查询适配器：复用一个受 Spring 生命周期管理的 AdminClient。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultConsoleKafkaLagQueryService implements ConsoleKafkaLagQueryPort {

  private static final long TIMEOUT_SECONDS = 10;
  private static final String KEY_GROUP_ID = "groupId";
  private static final String KEY_ERROR = "error";

  private final KafkaAdmin kafkaAdmin;
  private final ConsoleQueryCacheService cacheService;
  private final Object adminClientMonitor = new Object();
  private final AtomicBoolean stopping = new AtomicBoolean();
  private AdminClient adminClient;

  /** 列出所有 batch 相关 consumer group 的积压情况。 */
  @Override
  public List<ConsoleKafkaConsumerLagResponse> consumerGroupLags(String groupIdFilter) {
    return cacheService.getOrLoad(
        "kafka-lag:" + cacheSegment(groupIdFilter),
        ConsoleQueryCacheService.KAFKA_LAG_TTL,
        new TypeReference<List<ConsoleKafkaConsumerLagResponse>>() {},
        () -> loadConsumerGroupLags(groupIdFilter));
  }

  private List<ConsoleKafkaConsumerLagResponse> loadConsumerGroupLags(String groupIdFilter) {
    List<ConsoleKafkaConsumerLagResponse> result = new ArrayList<>();
    try {
      AdminClient admin = adminClient();
      Collection<GroupListing> groups =
          admin.listGroups().all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      for (GroupListing group : groups) {
        String groupId = group.groupId();
        if (EmptyChecks.isNotEmpty(groupIdFilter) && !groupId.contains(groupIdFilter)) {
          continue;
        }
        if (!groupId.startsWith("batch")) {
          continue;
        }
        try {
          result.add(queryGroupLag(admin, groupId));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn(
              "Kafka lag query interrupted for group {}: {}",
              groupId,
              SwallowedExceptionLogger.summary(e));
          result.add(error(groupId, "Kafka admin query interrupted: " + e.getMessage()));
          break;
        } catch (Exception e) {
          log.warn(
              "Failed to query lag for group {}: {}", groupId, SwallowedExceptionLogger.summary(e));
          result.add(error(groupId, e.getMessage()));
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.error("Kafka consumer group query was interrupted", e);
      result.add(error(null, "Kafka admin query interrupted: " + e.getMessage()));
    } catch (ExecutionException | TimeoutException e) {
      log.error("Failed to query Kafka consumer group list (Kafka may be unreachable)", e);
      result.add(error(null, "Failed to list consumer groups: " + e.getMessage()));
    }
    return result;
  }

  private AdminClient adminClient() {
    synchronized (adminClientMonitor) {
      if (stopping.get()) {
        throw new IllegalStateException("Kafka lag query service is stopping");
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

  private static String cacheSegment(String value) {
    return EmptyChecks.isBlank(value) ? "all" : ConsoleQueryCacheService.keySegment(value);
  }

  private ConsoleKafkaConsumerLagResponse queryGroupLag(AdminClient admin, String groupId)
      throws InterruptedException, ExecutionException, TimeoutException {
    ListConsumerGroupOffsetsResult offsetsResult = admin.listConsumerGroupOffsets(groupId);
    Map<TopicPartition, OffsetAndMetadata> committedOffsets =
        offsetsResult.partitionsToOffsetAndMetadata().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    Map<TopicPartition, OffsetSpec> endOffsetRequests = new LinkedHashMap<>();
    for (TopicPartition topicPartition : committedOffsets.keySet()) {
      endOffsetRequests.put(topicPartition, OffsetSpec.latest());
    }
    ListOffsetsResult endOffsetsResult = admin.listOffsets(endOffsetRequests);

    long totalLag = 0;
    List<ConsoleKafkaConsumerLagResponse.PartitionLag> partitionLags = new ArrayList<>();
    for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committedOffsets.entrySet()) {
      TopicPartition topicPartition = entry.getKey();
      long committed = entry.getValue().offset();
      long endOffset = endOffsetsResult
          .partitionResult(topicPartition)
          .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
          .offset();
      long lag = Math.max(0, endOffset - committed);
      totalLag += lag;
      if (lag > 0) {
        partitionLags.add(new ConsoleKafkaConsumerLagResponse.PartitionLag(
            topicPartition.topic(), topicPartition.partition(), committed, endOffset, lag));
      }
    }

    return new ConsoleKafkaConsumerLagResponse(
        groupId,
        totalLag,
        committedOffsets.size(),
        EmptyChecks.isEmpty(partitionLags) ? null : partitionLags,
        null);
  }

  private static ConsoleKafkaConsumerLagResponse error(String groupId, String message) {
    return new ConsoleKafkaConsumerLagResponse(groupId, null, null, null, message);
  }
}
