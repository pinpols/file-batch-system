package com.example.batch.console.domain.ops.service;

import com.example.batch.console.support.cache.ConsoleQueryCacheService;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
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

  /** 列出所有 batch 相关 consumer group 的积压情况。 */
  public List<Map<String, Object>> consumerGroupLags(String groupIdFilter) {
    return cacheService.getOrLoad(
        "kafka-lag:" + cacheSegment(groupIdFilter),
        ConsoleQueryCacheService.KAFKA_LAG_TTL,
        new TypeReference<List<Map<String, Object>>>() {},
        () -> loadConsumerGroupLags(groupIdFilter));
  }

  private List<Map<String, Object>> loadConsumerGroupLags(String groupIdFilter) {
    List<Map<String, Object>> result = new ArrayList<>();
    try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
      var groups = admin.listGroups().all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
        } catch (Exception e) {
          log.warn("Failed to query lag for group {}: {}", groupId, e.getMessage());
          Map<String, Object> errorEntry = new LinkedHashMap<>();
          errorEntry.put("groupId", groupId);
          errorEntry.put("error", e.getMessage());
          result.add(errorEntry);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.error("Kafka consumer group 查询被中断", e);
      Map<String, Object> errorEntry = new LinkedHashMap<>();
      errorEntry.put("error", "Kafka admin query interrupted: " + e.getMessage());
      result.add(errorEntry);
    } catch (ExecutionException | TimeoutException e) {
      log.error("Kafka consumer group 列表查询失败（Kafka 可能不可达）", e);
      Map<String, Object> errorEntry = new LinkedHashMap<>();
      errorEntry.put("error", "Failed to list consumer groups: " + e.getMessage());
      result.add(errorEntry);
    }
    return result;
  }

  private static String cacheSegment(String value) {
    return value == null || value.isBlank() ? "all" : value;
  }

  private Map<String, Object> queryGroupLag(AdminClient admin, String groupId)
      throws InterruptedException, ExecutionException, TimeoutException {
    ListConsumerGroupOffsetsResult offsetsResult = admin.listConsumerGroupOffsets(groupId);
    Map<TopicPartition, OffsetAndMetadata> committedOffsets =
        offsetsResult.partitionsToOffsetAndMetadata().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    // Query end offsets for the same partitions
    Map<TopicPartition, OffsetSpec> endOffsetRequests = new LinkedHashMap<>();
    committedOffsets.keySet().forEach(tp -> endOffsetRequests.put(tp, OffsetSpec.latest()));
    ListOffsetsResult endOffsetsResult = admin.listOffsets(endOffsetRequests);

    long totalLag = 0;
    List<Map<String, Object>> partitionLags = new ArrayList<>();
    for (var entry : committedOffsets.entrySet()) {
      TopicPartition tp = entry.getKey();
      long committed = entry.getValue().offset();
      long endOffset =
          endOffsetsResult.partitionResult(tp).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).offset();
      long lag = Math.max(0, endOffset - committed);
      totalLag += lag;
      if (lag > 0) {
        Map<String, Object> partitionInfo = new LinkedHashMap<>();
        partitionInfo.put("topic", tp.topic());
        partitionInfo.put("partition", tp.partition());
        partitionInfo.put("committedOffset", committed);
        partitionInfo.put("endOffset", endOffset);
        partitionInfo.put("lag", lag);
        partitionLags.add(partitionInfo);
      }
    }

    Map<String, Object> groupInfo = new LinkedHashMap<>();
    groupInfo.put("groupId", groupId);
    groupInfo.put("totalLag", totalLag);
    groupInfo.put("partitionCount", committedOffsets.size());
    if (!partitionLags.isEmpty()) {
      groupInfo.put("partitionsWithLag", partitionLags);
    }
    return groupInfo;
  }
}
