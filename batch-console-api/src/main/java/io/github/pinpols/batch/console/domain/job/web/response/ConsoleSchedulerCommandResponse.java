package io.github.pinpols.batch.console.domain.job.web.response;

import java.util.Map;

/** 调度器状态 / 全局暂停恢复动作结果（仅 {@code status} 一个字段）。 */
public record ConsoleSchedulerCommandResponse(String status) {

  public static ConsoleSchedulerCommandResponse from(Map<String, String> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleSchedulerCommandResponse(JobResponseFieldReader.stringValue(row, "status"));
  }
}
