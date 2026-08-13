package io.github.pinpols.batch.console.application.ops.response;

import io.github.pinpols.batch.console.support.web.ConsoleResponseFieldReader;
import java.util.Map;

/** 批量日治理动作（FREEZE / RELEASE / SKIP / REOPEN / CLOSE）结果。 */
public record ConsoleBatchDayOperateResponse(
    Long batchDayId, String dayStatus, Boolean frozen, Integer releasedLaunchCount) {

  public static ConsoleBatchDayOperateResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleBatchDayOperateResponse(
        ConsoleResponseFieldReader.longValue(row, "batchDayId"),
        ConsoleResponseFieldReader.stringValue(row, "dayStatus"),
        ConsoleResponseFieldReader.booleanValue(row, "frozen"),
        ConsoleResponseFieldReader.integerValue(row, "releasedLaunchCount"));
  }
}
