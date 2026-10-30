package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Kafka consumer group 积压响应（列表元素）。列表混合两种历史形态：正常组 {@code
 * {groupId,totalLag,partitionCount[,partitionsWithLag]}} 与出错条目 {@code {[groupId,]error}}。
 * 使用 {@code NON_NULL} 保留各形态的键集。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleKafkaConsumerLagResponse(
    String groupId,
    Long totalLag,
    Integer partitionCount,
    List<PartitionLag> partitionsWithLag,
    String error) {

  public record PartitionLag(
      String topic, Integer partition, Long committedOffset, Long endOffset, Long lag) {}
}
