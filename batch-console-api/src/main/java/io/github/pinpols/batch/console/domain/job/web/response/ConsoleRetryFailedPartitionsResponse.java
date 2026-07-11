package io.github.pinpols.batch.console.domain.job.web.response;

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
        JobResponseFieldReader.longValue(row, "id"),
        JobResponseFieldReader.stringValue(row, "instanceNo"),
        JobResponseFieldReader.integerValue(row, "requested"),
        JobResponseFieldReader.integerValue(row, "retried"),
        JobResponseFieldReader.integerValue(row, "conflicts"),
        JobResponseFieldReader.longListValue(row, "partitionIds"));
  }
}
