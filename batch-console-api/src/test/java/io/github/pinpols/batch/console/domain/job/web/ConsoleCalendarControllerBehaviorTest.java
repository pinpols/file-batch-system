package io.github.pinpols.batch.console.domain.job.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.job.application.ConsoleCalendarApplicationService;
import io.github.pinpols.batch.console.domain.job.application.contract.request.CalendarSaveRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.HolidayImportRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.HolidaySaveRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleCalendarResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleHolidayResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * P1: ConsoleCalendarController CRUD + holiday 子表行为测试(原 ValidationTest 仅守 @ValidResourceCode 约束)。
 */
@DisplayName("日历控制器: 日历与节假日子资源的增改查删转发行为")
class ConsoleCalendarControllerBehaviorTest {

  private final ConsoleCalendarApplicationService service =
      mock(ConsoleCalendarApplicationService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleCalendarController(service, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("未传筛选条件时按默认页码与页大小下查, 回包与查询结果一致")
  void shouldDelegateListWithDefaultPaging_whenFiltersAbsent() throws Exception {
    when(service.list(eq("ta"), any(), any(), eq(1), eq(20)))
        .thenReturn(new PageResponse<>(0L, 1, 20, List.of()));
    mockMvc
        .perform(get("/api/console/calendars").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
    verify(service).list("ta", null, null, 1, 20);
  }

  @Test
  @DisplayName("新建成功后回包携带持久化标识与日历编码")
  void shouldReturnPersistedRow_whenCreateSucceeds() throws Exception {
    when(service.create(any(CalendarSaveRequest.class)))
        .thenReturn(new ConsoleCalendarResponse(
            1L,
            "ta",
            "default-calendar",
            "默认",
            "Asia/Shanghai",
            null,
            null,
            null,
            true,
            null,
            null,
            null));
    mockMvc
        .perform(
            post("/api/console/calendars")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"ta\",\"calendarCode\":\"default-calendar\",\"calendarName\":\"默认\",\"timezone\":\"Asia/Shanghai\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(1))
        .andExpect(jsonPath("$.data.calendarCode").value("default-calendar"));
  }

  @Test
  @DisplayName("更新请求以路径标识定位目标日历, 报文原样下发给下游")
  void shouldPassPathId_whenUpdateRequested() throws Exception {
    when(service.update(eq(7L), any(CalendarSaveRequest.class)))
        .thenReturn(new ConsoleCalendarResponse(
            7L, "ta", "c1", "n", "Asia/Shanghai", null, null, null, true, null, null, null));
    mockMvc
        .perform(
            put("/api/console/calendars/7")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"ta\",\"calendarCode\":\"c1\",\"calendarName\":\"n\",\"timezone\":\"Asia/Shanghai\"}"))
        .andExpect(status().isOk());
    verify(service).update(eq(7L), any(CalendarSaveRequest.class));
  }

  @Test
  @DisplayName("启停操作以路径标识与报文中的目标状态下发下游")
  void shouldPassBody_whenToggleRequested() throws Exception {
    mockMvc
        .perform(patch("/api/console/calendars/9/enabled")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\",\"enabled\":true}"))
        .andExpect(status().isOk());
    verify(service).toggle(9L, "ta", true);
  }

  @Test
  @DisplayName("按日历标识查询节假日列表, 回包为该租户下的明细")
  void shouldReturnHolidayList_whenCalendarHasHolidays() throws Exception {
    when(service.holidays(3L, "ta"))
        .thenReturn(List.of(new ConsoleHolidayResponse(
            1L, 3L, LocalDate.parse("2026-05-20"), "HOLIDAY", "N1", null, null, null)));
    mockMvc
        .perform(get("/api/console/calendars/3/holidays").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].holidayName").value("N1"));
  }

  @Test
  @DisplayName("节假日导入以路径标识定位目标日历, 请求报文下发给下游")
  void shouldPassPathId_whenImportingHolidays() throws Exception {
    mockMvc
        .perform(
            post("/api/console/calendars/3/holidays")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"ta\",\"items\":[{\"bizDate\":\"2026-05-20\",\"dayType\":\"HOLIDAY\"}]}"))
        .andExpect(status().isOk());
    verify(service).importHolidays(eq(3L), any(HolidayImportRequest.class));
  }

  @Test
  @DisplayName("删除节假日同时下发日历标识、节假日标识与租户")
  void shouldPassBothIdsAndTenant_whenDeletingHoliday() throws Exception {
    mockMvc
        .perform(delete("/api/console/calendars/3/holidays/5").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(service).deleteHoliday(3L, 5L, "ta");
  }

  @Test
  @DisplayName("更新节假日时两个路径标识与请求报文一并下发, 回包为更新后的明细")
  void shouldPassBothIdsAndBody_whenUpdatingHoliday() throws Exception {
    when(service.updateHoliday(eq(3L), eq(5L), any(HolidaySaveRequest.class)))
        .thenReturn(new ConsoleHolidayResponse(
            5L, 3L, LocalDate.parse("2026-05-20"), "HOLIDAY", "N1", null, null, null));
    mockMvc
        .perform(
            put("/api/console/calendars/3/holidays/5")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"ta\",\"bizDate\":\"2026-05-20\",\"dayType\":\"HOLIDAY\",\"holidayName\":\"N1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(5));
  }
}
