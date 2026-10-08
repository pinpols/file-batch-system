package io.github.pinpols.batch.console.domain.job.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.job.application.ConsoleCalendarApplicationService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** 守护 ConsoleCalendarController 的 CalendarSaveRequest.calendarCode 走 @ValidResourceCode 拦截。 */
@DisplayName("日历控制器: 日历编码格式校验的拒绝与通过路径")
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
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "ta", "tester", null, "127.0.0.1"));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleCalendarController(service, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  private String body(String calendarCode) {
    return """
        {
          "tenantId": "ta",
          "calendarCode": "%s",
          "calendarName": "cal",
          "timezone": "Asia/Shanghai"
        }
        """.formatted(calendarCode).stripTrailing();
  }

  @Test
  @DisplayName("日历编码含空格时返回参数校验失败, 且不写入下游")
  void rejects_calendarCode_with_space() throws Exception {
    mockMvc
        .perform(
            post("/api/console/calendars").contentType(APPLICATION_JSON).content(body("q q q")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verify(service, never()).create(ArgumentMatchers.any());
  }

  @Test
  @DisplayName("日历编码含中文时返回参数校验失败")
  void rejects_calendarCode_chinese() throws Exception {
    mockMvc
        .perform(post("/api/console/calendars").contentType(APPLICATION_JSON).content(body("中文日历")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("日历编码以数字开头时返回参数校验失败")
  void rejects_calendarCode_starts_with_digit() throws Exception {
    mockMvc
        .perform(post("/api/console/calendars").contentType(APPLICATION_JSON).content(body("1abc")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("日历编码为空时返回参数校验失败")
  void rejects_calendarCode_blank() throws Exception {
    mockMvc
        .perform(post("/api/console/calendars").contentType(APPLICATION_JSON).content(body("")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("日历编码符合格式要求时创建成功并触达下游")
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
