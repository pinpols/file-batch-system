package io.github.pinpols.batch.console.domain.job.web.response;

import java.util.Map;

/** 分区级动作（cancel / retry）结果。 */
public record ConsolePartitionActionResponse(Long id, String status) {

  public static ConsolePartitionActionResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsolePartitionActionResponse(
        JobResponseFieldReader.longValue(row, "id"),
        JobResponseFieldReader.stringValue(row, "status"));
  }
}
