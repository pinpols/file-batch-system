package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.application.contract.request.config.ResourceQueueCreateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchWindowCreateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.CalendarSaveRequest;
import io.github.pinpols.batch.console.domain.job.infrastructure.DefaultConsoleBatchWindowApplicationService;
import io.github.pinpols.batch.console.domain.job.infrastructure.DefaultConsoleCalendarApplicationService;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.param.BatchWindowInsertParam;
import io.github.pinpols.batch.console.domain.job.param.BusinessCalendarInsertParam;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.param.ResourceQueueInsertParam;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 保留原Map主键读取的失败边界：插入未回填id时立即失败，不继续查询并返回空成功。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("配置创建：主键回填异常不能被空响应掩盖")
class ConsoleWindowQueueGeneratedIdTest {

  @Mock
  private BatchWindowMapper windows;

  @Mock
  private ResourceQueueMapper queues;

  @Mock
  private ConsoleTenantGuard tenants;

  @Mock
  private ConsoleConfigCacheInvalidationService cache;

  @BeforeEach
  void authorizeTenant() {
    when(tenants.resolveTenant("ta")).thenReturn("ta");
  }

  @Test
  @DisplayName("窗口缺少回填主键时立即失败，保留原缓存失效位置且不继续按空主键读取")
  void shouldFailBeforeRead_whenWindowIdWasNotGenerated() {
    when(windows.insert(any(BatchWindowInsertParam.class))).thenReturn(1);
    DefaultConsoleBatchWindowApplicationService service =
        new DefaultConsoleBatchWindowApplicationService(windows, tenants, cache);
    BatchWindowCreateRequest request = new BatchWindowCreateRequest();
    request.setTenantId("ta");
    request.setWindowCode("fixture_window");
    request.setWindowName("窗口夹具");
    request.setTimezone("UTC");
    request.setStartTime("01:00:00");
    request.setEndTime("02:00:00");

    assertThatThrownBy(() -> service.create(request)).isInstanceOf(NullPointerException.class);

    verify(cache).evictBatchWindow("ta", "fixture_window");
    verify(windows, never()).selectById(any(), any());
  }

  @Test
  @DisplayName("队列缺少回填主键时立即失败，不继续按空主键读取并返回空成功")
  void shouldFailBeforeRead_whenQueueIdWasNotGenerated() {
    when(queues.insert(any(ResourceQueueInsertParam.class))).thenReturn(1);
    DefaultConsoleResourceQueueApplicationService service =
        new DefaultConsoleResourceQueueApplicationService(queues, tenants);
    ResourceQueueCreateRequest request = new ResourceQueueCreateRequest();
    request.setTenantId("ta");
    request.setQueueCode("fixture_queue");
    request.setQueueName("队列夹具");
    request.setQueueType("MIXED");

    assertThatThrownBy(() -> service.create(request)).isInstanceOf(NullPointerException.class);

    verify(queues, never()).selectById(any(), any());
  }

  @Test
  @DisplayName("日历缺少回填主键时立即失败，缓存失效仍在原位置且不继续读取空主键")
  void shouldFailBeforeRead_whenCalendarIdWasNotGenerated() {
    BusinessCalendarMapper calendars = mock(BusinessCalendarMapper.class);
    CalendarHolidayMapper holidays = mock(CalendarHolidayMapper.class);
    when(calendars.insert(any(BusinessCalendarInsertParam.class))).thenReturn(1);
    DefaultConsoleCalendarApplicationService service =
        new DefaultConsoleCalendarApplicationService(calendars, holidays, tenants, cache);
    CalendarSaveRequest request = new CalendarSaveRequest();
    request.setTenantId("ta");
    request.setCalendarCode("fixture_calendar");
    request.setCalendarName("日历夹具");
    request.setTimezone("UTC");

    assertThatThrownBy(() -> service.create(request)).isInstanceOf(NullPointerException.class);

    verify(cache).evictBusinessCalendar("ta", "fixture_calendar");
    verify(calendars, never()).selectById(any(), any());
  }
}
