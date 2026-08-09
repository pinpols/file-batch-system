package io.github.pinpols.batch.console.domain.job.web.response;

import io.github.pinpols.batch.console.support.web.ConsoleResponseFieldReader;
import java.util.Map;

/** 调度器状态 / 全局暂停恢复动作结果（仅 {@code status} 一个字段）。 */
public record ConsoleSchedulerCommandResponse(String status) {

  public static ConsoleSchedulerCommandResponse from(Map<String, String> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleSchedulerCommandResponse(
        ConsoleResponseFieldReader.stringValue(row, "status"));
  }
}
