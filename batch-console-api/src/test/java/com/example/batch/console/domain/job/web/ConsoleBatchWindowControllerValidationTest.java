package com.example.batch.console.domain.job.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.job.application.ConsoleBatchWindowApplicationService;
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

/**
 * 守护 ConsoleBatchWindowController 的 BatchWindowCreateRequest.windowCode 走 @ValidResourceCode 拦截。
 */
class ConsoleBatchWindowControllerValidationTest {

  private final ConsoleBatchWindowApplicationService service =
      mock(ConsoleBatchWindowApplicationService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory);

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "ta", "tester", null, "127.0.0.1"));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(new ConsoleBatchWindowController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  private String body(String windowCode) {
    return "{"
        + "\"tenantId\":\"ta\","
        + "\"windowCode\":\""
        + windowCode
        + "\","
        + "\"windowName\":\"win\","
        + "\"timezone\":\"Asia/Shanghai\","
        + "\"startTime\":\"00:00\","
        + "\"endTime\":\"23:59\""
        + "}";
  }

  @Test
  void rejects_windowCode_with_space() throws Exception {
    mockMvc
        .perform(
            post("/api/console/batch-windows").contentType(APPLICATION_JSON).content(body("q q q")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verify(service, never()).create(ArgumentMatchers.any());
  }

  @Test
  void rejects_windowCode_chinese() throws Exception {
    mockMvc
        .perform(
            post("/api/console/batch-windows").contentType(APPLICATION_JSON).content(body("时间窗")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void rejects_windowCode_starts_with_digit() throws Exception {
    mockMvc
        .perform(
            post("/api/console/batch-windows").contentType(APPLICATION_JSON).content(body("9win")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void rejects_windowCode_blank() throws Exception {
    mockMvc
        .perform(post("/api/console/batch-windows").contentType(APPLICATION_JSON).content(body("")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void accepts_valid_windowCode() throws Exception {
    when(service.create(ArgumentMatchers.any())).thenReturn(null);
    mockMvc
        .perform(
            post("/api/console/batch-windows")
                .contentType(APPLICATION_JSON)
                .content(body("win_ok_01")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
    verify(service).create(ArgumentMatchers.any());
  }
}
