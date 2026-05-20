package com.example.batch.console.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.application.config.ConsoleCalendarApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadata;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** 守护 ConsoleCalendarController 的 CalendarSaveRequest.calendarCode 走 @ValidResourceCode 拦截。 */
class ConsoleCalendarControllerValidationTest {

  private final ConsoleCalendarApplicationService service =
      mock(ConsoleCalendarApplicationService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        new ConsoleApiExceptionHandler(responseFactory, new BatchSecurityProperties());

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "ta", "tester", null, "127.0.0.1"));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(new ConsoleCalendarController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  private String body(String calendarCode) {
    return "{"
        + "\"tenantId\":\"ta\","
        + "\"calendarCode\":\""
        + calendarCode
        + "\","
        + "\"calendarName\":\"cal\","
        + "\"timezone\":\"Asia/Shanghai\""
        + "}";
  }

  @Test
  void rejects_calendarCode_with_space() throws Exception {
    mockMvc
        .perform(
            post("/api/console/calendars").contentType(APPLICATION_JSON).content(body("q q q")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verify(service, never()).create(ArgumentMatchers.any());
  }

  @Test
  void rejects_calendarCode_chinese() throws Exception {
    mockMvc
        .perform(post("/api/console/calendars").contentType(APPLICATION_JSON).content(body("中文日历")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void rejects_calendarCode_starts_with_digit() throws Exception {
    mockMvc
        .perform(post("/api/console/calendars").contentType(APPLICATION_JSON).content(body("1abc")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void rejects_calendarCode_blank() throws Exception {
    mockMvc
        .perform(post("/api/console/calendars").contentType(APPLICATION_JSON).content(body("")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void accepts_valid_calendarCode() throws Exception {
    when(service.create(ArgumentMatchers.any())).thenReturn(null);
    mockMvc
        .perform(
            post("/api/console/calendars").contentType(APPLICATION_JSON).content(body("cal_ok_01")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
    verify(service).create(ArgumentMatchers.any());
  }
}
