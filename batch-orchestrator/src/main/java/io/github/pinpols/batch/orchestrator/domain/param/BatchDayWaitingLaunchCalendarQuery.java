package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.LocalDate;
import java.util.Objects;
import lombok.Builder;

/** 同租户日历业务日下的等待状态查询。 */
@Builder
public record BatchDayWaitingLaunchCalendarQuery(
    String tenantId, String calendarCode, LocalDate bizDate, String waitStatus, int limit) {

  public BatchDayWaitingLaunchCalendarQuery {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(calendarCode, "calendarCode");
  }
}
