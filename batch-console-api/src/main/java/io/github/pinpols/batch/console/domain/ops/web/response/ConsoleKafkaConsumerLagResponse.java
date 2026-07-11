package io.github.pinpols.batch.console.domain.ops.web.response;

import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.integerValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.stringValue;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Kafka consumer group 积压响应（列表元素）。列表混合两种历史形态：正常组 {@code
 * {groupId,totalLag,partitionCount[,partitionsWithLag]}} 与出错条目 {@code {[groupId,]error}}。service 用
 * LinkedHashMap 条件 put，缺失键不出现（如无积压时不含 partitionsWithLag、正常时不含 error）→ 用 {@code NON_NULL}
 * 精确保留每种形态的键集。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleKafkaConsumerLagResponse(
    String groupId,
    Long totalLag,
    Integer partitionCount,
    List<PartitionLag> partitionsWithLag,
    String error) {

  public record PartitionLag(
      String topic, Integer partition, Long committedOffset, Long endOffset, Long lag) {
    static PartitionLag from(Map<String, Object> row) {
      return new PartitionLag(
          stringValue(row, "topic"),
          integerValue(row, "partition"),
          longValue(row, "committedOffset"),
          longValue(row, "endOffset"),
          longValue(row, "lag"));
    }
  }

  public static ConsoleKafkaConsumerLagResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    // partitionsWithLag 缺失时保持 null（NON_NULL 省略键），不能默认成空列表，否则键集漂移。
    List<PartitionLag> partitions =
        row.containsKey("partitionsWithLag")
            ? mapList(row.get("partitionsWithLag")).stream().map(PartitionLag::from).toList()
            : null;
    return new ConsoleKafkaConsumerLagResponse(
        stringValue(row, "groupId"),
        longValue(row, "totalLag"),
        integerValue(row, "partitionCount"),
        partitions,
        stringValue(row, "error"));
  }
}
