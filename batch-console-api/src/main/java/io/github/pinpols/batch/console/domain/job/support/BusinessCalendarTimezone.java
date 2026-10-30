package io.github.pinpols.batch.console.domain.job.support;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.DateTimeException;
import java.time.ZoneId;

/** 日历实际保存的时区必须能被业务日期计算使用。 */
public final class BusinessCalendarTimezone {
  private BusinessCalendarTimezone() {}

  public static boolean isValid(String value) {
    if (EmptyChecks.isNull(value)) return false;
    try {
      ZoneId.of(value);
      return true;
    } catch (DateTimeException invalid) {
      return false;
    }
  }

  public static void requireValid(String value) {
    if (!isValid(value)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey(), "timezone");
    }
  }
}
