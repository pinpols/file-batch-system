package io.github.pinpols.batch.console.domain.job.web.response;

import io.github.pinpols.batch.console.support.web.ConsoleResponseFieldReader;
import java.util.Map;

/** 触发器运维动作（register / unregister / pause / resume）结果。 */
public record ConsoleTriggerActionResponse(String tenantId, String jobCode, String status) {

  public static ConsoleTriggerActionResponse from(Map<String, String> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleTriggerActionResponse(
        ConsoleResponseFieldReader.stringValue(row, "tenantId"),
        ConsoleResponseFieldReader.stringValue(row, "jobCode"),
        ConsoleResponseFieldReader.stringValue(row, "status"));
  }
}
