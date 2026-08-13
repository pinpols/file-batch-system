package io.github.pinpols.batch.console.application.ops.response;

import io.github.pinpols.batch.console.support.web.ConsoleResponseFieldReader;
import java.util.List;
import java.util.Map;

/** 批量重试实例全部 FAILED 分区的结果。 */
public record ConsoleRetryFailedPartitionsResponse(
    Long id,
    String instanceNo,
    Integer requested,
    Integer retried,
    Integer conflicts,
    List<Long> partitionIds) {

  public static ConsoleRetryFailedPartitionsResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleRetryFailedPartitionsResponse(
        ConsoleResponseFieldReader.longValue(row, "id"),
        ConsoleResponseFieldReader.stringValue(row, "instanceNo"),
        ConsoleResponseFieldReader.integerValue(row, "requested"),
        ConsoleResponseFieldReader.integerValue(row, "retried"),
        ConsoleResponseFieldReader.integerValue(row, "conflicts"),
        ConsoleResponseFieldReader.longListValue(row, "partitionIds"));
  }
}
