package com.example.batch.console.domain.job.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.job.application.ConsoleCalendarApplicationService;
import com.example.batch.console.domain.job.web.request.CalendarSaveRequest;
import com.example.batch.console.domain.job.web.request.HolidayImportRequest;
import com.example.batch.console.domain.job.web.request.HolidaySaveRequest;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * P1: ConsoleCalendarController CRUD + holiday 子表行为测试(原 ValidationTest 仅守 @ValidResourceCode 约束)。
 */
class ConsoleCalendarControllerBehaviorTest {

  private final ConsoleCalendarApplicationService service =
      mock(ConsoleCalendarApplicationService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ConsoleCalendarController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void listShouldDelegateWithDefaultPagingAndOptionalFilters() throws Exception {
    when(service.list(eq("ta"), any(), any(), eq(1), eq(20)))
        .thenReturn(new PageResponse<>(0L, 1, 20, List.of()));
    mockMvc
        .perform(get("/api/console/calendars").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
    verify(service).list(eq("ta"), eq(null), eq(null), eq(1), eq(20));
  }

  @Test
  void createShouldReturnPersistedRow() throws Exception {
    when(service.create(any(CalendarSaveRequest.class)))
        .thenReturn(Map.of("id", 1L, "calendarCode", "default-calendar"));
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
  void updateShouldPassPathIdToService() throws Exception {
    when(service.update(eq(7L), any(CalendarSaveRequest.class))).thenReturn(Map.of("id", 7L));
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
  void toggleShouldDelegateWithIdTenantEnabled() throws Exception {
    mockMvc
        .perform(
            post("/api/console/calendars/9/toggle")
                .param("tenantId", "ta")
                .param("enabled", "false"))
        .andExpect(status().isOk());
    verify(service).toggle(9L, "ta", false);
  }

  @Test
  void holidaysShouldReturnListForTenant() throws Exception {
    when(service.holidays(3L, "ta"))
        .thenReturn(List.of(Map.of("holidayDate", "2026-05-20", "holidayName", "N1")));
    mockMvc
        .perform(get("/api/console/calendars/3/holidays").param("tenantId", "ta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].holidayName").value("N1"));
  }

  @Test
  void importHolidaysShouldPassPathId() throws Exception {
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
  void deleteHolidayShouldPassBothIdsAndTenant() throws Exception {
    mockMvc
        .perform(delete("/api/console/calendars/3/holidays/5").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(service).deleteHoliday(3L, 5L, "ta");
  }

  @Test
  void updateHolidayShouldPassBothIdsAndBody() throws Exception {
    when(service.updateHoliday(eq(3L), eq(5L), any(HolidaySaveRequest.class)))
        .thenReturn(Map.of("id", 5L));
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
