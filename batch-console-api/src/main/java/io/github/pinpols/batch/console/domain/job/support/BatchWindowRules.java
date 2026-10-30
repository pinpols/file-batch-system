package io.github.pinpols.batch.console.domain.job.support;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;

/** 窗口写入的共同约束；调用方先应用默认值或合并已有值，此处不改写配置。 */
public final class BatchWindowRules {

  /** 非跨日区间错误的固定字段上下文，供预览按需回查已有跨日设置。 */
  public static final String WINDOW_RANGE_FIELD = "windowRange";

  private BatchWindowRules() {}

  public static void validate(
      String timezone, String startTime, String endTime, Boolean allowCrossDay) {
    if (EmptyChecks.isNull(timezone)) {
      throw invalid("timezone");
    }
    try {
      ZoneId.of(timezone);
    } catch (DateTimeException invalid) {
      throw invalid("timezone");
    }
    LocalTime start = parseTime(startTime, "startTime");
    LocalTime end = parseTime(endTime, "endTime");
    boolean invalidSameDayRange = !Boolean.TRUE.equals(allowCrossDay) && !start.isBefore(end);
    if (invalidSameDayRange) {
      throw invalid(WINDOW_RANGE_FIELD);
    }
  }

  private static LocalTime parseTime(String value, String field) {
    if (EmptyChecks.isNull(value)) {
      throw invalid(field);
    }
    try {
      return LocalTime.parse(value);
    } catch (DateTimeException invalid) {
      throw invalid(field);
    }
  }

  private static BizException invalid(String field) {
    return BizException.of(
        ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey(), field);
  }
}
