package io.github.pinpols.batch.console.domain.ops.service;

import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleKafkaConsumerLagResponse;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

/** Kafka consumer group lag 查询服务：利用 KafkaAdmin 获取消费积压信息。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleKafkaLagQueryService {

  private final KafkaAdmin kafkaAdmin;
  private final ConsoleQueryCacheService cacheService;

  private static final long TIMEOUT_SECONDS = 10;
  private static final String KEY_GROUP_ID = "groupId";
  private static final String KEY_ERROR = "error";

  /** 列出所有 batch 相关 consumer group 的积压情况。 */
  public List<ConsoleKafkaConsumerLagResponse> consumerGroupLags(String groupIdFilter) {
    return cacheService.getOrLoad(
        "kafka-lag:" + cacheSegment(groupIdFilter),
        ConsoleQueryCacheService.KAFKA_LAG_TTL,
        new com.fasterxml.jackson.core.type.TypeReference<
            List<ConsoleKafkaConsumerLagResponse>>() {},
        () -> loadConsumerGroupLags(groupIdFilter));
  }

  private List<ConsoleKafkaConsumerLagResponse> loadConsumerGroupLags(String groupIdFilter) {
    List<ConsoleKafkaConsumerLagResponse> result = new ArrayList<>();
    try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
      Collection<GroupListing> groups =
          admin.listGroups().all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      for (GroupListing group : groups) {
        String groupId = group.groupId();
        if (groupIdFilter != null && !groupIdFilter.isEmpty() && !groupId.contains(groupIdFilter)) {
          continue;
        }
        if (!groupId.startsWith("batch")) {
          continue;
        }
        try {
          result.add(queryGroupLag(admin, groupId));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("Kafka lag query interrupted for group {}: {}", groupId, e.getMessage());
          result.add(error(groupId, "Kafka admin query interrupted: " + e.getMessage()));
          break;
        } catch (Exception e) {
          log.warn("Failed to query lag for group {}: {}", groupId, e.getMessage());
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

  private static String cacheSegment(String value) {
    return value == null || value.isBlank() ? "all" : ConsoleQueryCacheService.keySegment(value);
  }

  private ConsoleKafkaConsumerLagResponse queryGroupLag(AdminClient admin, String groupId)
      throws InterruptedException, ExecutionException, TimeoutException {
    ListConsumerGroupOffsetsResult offsetsResult = admin.listConsumerGroupOffsets(groupId);
    Map<TopicPartition, OffsetAndMetadata> committedOffsets =
        offsetsResult.partitionsToOffsetAndMetadata().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    // 查询相同分区的 end offset
    Map<TopicPartition, OffsetSpec> endOffsetRequests = new LinkedHashMap<>();
    for (TopicPartition topicPartition : committedOffsets.keySet()) {
      endOffsetRequests.put(topicPartition, OffsetSpec.latest());
    }
    ListOffsetsResult endOffsetsResult = admin.listOffsets(endOffsetRequests);

    long totalLag = 0;
    List<ConsoleKafkaConsumerLagResponse.PartitionLag> partitionLags = new ArrayList<>();
    for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committedOffsets.entrySet()) {
      TopicPartition tp = entry.getKey();
      long committed = entry.getValue().offset();
      long endOffset = endOffsetsResult
          .partitionResult(tp)
          .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
          .offset();
      long lag = Math.max(0, endOffset - committed);
      totalLag += lag;
      if (lag > 0) {
        partitionLags.add(new ConsoleKafkaConsumerLagResponse.PartitionLag(
            tp.topic(), tp.partition(), committed, endOffset, lag));
      }
    }

    return new ConsoleKafkaConsumerLagResponse(
        groupId,
        totalLag,
        committedOffsets.size(),
        partitionLags.isEmpty() ? null : partitionLags,
        null);
  }

  private static ConsoleKafkaConsumerLagResponse error(String groupId, String message) {
    return new ConsoleKafkaConsumerLagResponse(groupId, null, null, null, message);
  }
}
