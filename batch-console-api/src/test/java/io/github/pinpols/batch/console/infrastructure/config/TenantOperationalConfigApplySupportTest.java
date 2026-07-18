package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertRoutingConfigMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TenantOperationalConfigApplySupportTest {

  @Test
  void updatingCalendarShouldReplaceHolidayRowsInOriginalOrder() {
    ResourceQueueMapper resourceQueueMapper = mock(ResourceQueueMapper.class);
    BatchWindowMapper batchWindowMapper = mock(BatchWindowMapper.class);
    BusinessCalendarMapper calendarMapper = mock(BusinessCalendarMapper.class);
    CalendarHolidayMapper holidayMapper = mock(CalendarHolidayMapper.class);
    TenantQuotaPolicyMapper quotaMapper = mock(TenantQuotaPolicyMapper.class);
    AlertRoutingConfigMapper alertMapper = mock(AlertRoutingConfigMapper.class);
    TenantOperationalConfigApplySupport support =
        new TenantOperationalConfigApplySupport(
            resourceQueueMapper,
            batchWindowMapper,
            calendarMapper,
            holidayMapper,
            quotaMapper,
            alertMapper);
    BusinessCalendarSpec spec = new BusinessCalendarSpec();
    spec.setCalendarCode("cn-settlement");
    spec.setCalendarName("CN Settlement");
    spec.setHolidays(List.of("2026-01-01", "2026-02-17"));
    when(calendarMapper.selectActiveByTenantAndCalendarCode("tenant-a", "cn-settlement"))
        .thenReturn(Map.of("id", 91L));

    support.upsertBusinessCalendar("tenant-a", spec, "admin", 91L);

    verify(calendarMapper).upsertBusinessCalendar(any());
    verify(holidayMapper).deleteByCalendarId(91L);
    ArgumentCaptor<List<Map<String, Object>>> rows = ArgumentCaptor.captor();
    verify(holidayMapper).batchInsert(rows.capture());
    assertThat(rows.getValue())
        .extracting(row -> row.get("holiday_date"))
        .containsExactly("2026-01-01", "2026-02-17");
    assertThat(rows.getValue())
        .allSatisfy(
            row -> {
              assertThat(row).containsEntry("calendar_id", 91L);
              assertThat(row).containsEntry("created_by", "admin");
            });
  }
}
