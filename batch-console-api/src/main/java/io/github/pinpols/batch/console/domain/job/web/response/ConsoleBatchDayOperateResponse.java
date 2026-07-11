package io.github.pinpols.batch.console.domain.job.web.response;

import java.util.Map;

/** 批量日治理动作（FREEZE / RELEASE / SKIP / REOPEN / CLOSE）结果。 */
public record ConsoleBatchDayOperateResponse(
    Long batchDayId, String dayStatus, Boolean frozen, Integer releasedLaunchCount) {

  public static ConsoleBatchDayOperateResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleBatchDayOperateResponse(
        JobResponseFieldReader.longValue(row, "batchDayId"),
        JobResponseFieldReader.stringValue(row, "dayStatus"),
        JobResponseFieldReader.booleanValue(row, "frozen"),
        JobResponseFieldReader.integerValue(row, "releasedLaunchCount"));
  }
}
