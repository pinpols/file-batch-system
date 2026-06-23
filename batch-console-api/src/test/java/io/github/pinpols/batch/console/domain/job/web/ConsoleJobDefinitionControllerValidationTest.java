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
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobDefinitionApplicationService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * 守护 ConsoleJobDefinitionController 的请求体 Bean Validation 在 controller 入口生效。
 *
 * <p>历史异常数据案例：FE 已加 jobCode pattern,但 BE 未拦,被直接 INSERT `q q q` 含空格的 job_code,
 * 后续路由跳转崩。本测试守护 @ValidResourceCode 在 jobCode / newJobCode 两条入口都生效。
 */
class ConsoleJobDefinitionControllerValidationTest {

  private final ConsoleJobDefinitionApplicationService service =
      mock(ConsoleJobDefinitionApplicationService.class);
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

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleJobDefinitionController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  private String createBody(String jobCode) {
    return "{"
        + "\"tenantId\":\"ta\","
        + "\"jobCode\":\""
        + jobCode
        + "\","
        + "\"jobName\":\"test\","
        + "\"jobType\":\"GENERAL\","
        + "\"scheduleType\":\"MANUAL\""
        + "}";
  }

  @Test
  void rejects_jobCode_with_space() throws Exception {
    mockMvc
        .perform(
            post("/api/console/job-definitions")
                .contentType(APPLICATION_JSON)
                .content(createBody("q q q")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    verify(service, never()).create(ArgumentMatchers.any());
  }

  @Test
  void rejects_jobCode_chinese() throws Exception {
    mockMvc
        .perform(
            post("/api/console/job-definitions")
                .contentType(APPLICATION_JSON)
                .content(createBody("中文测试")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void rejects_jobCode_starts_with_digit() throws Exception {
    mockMvc
        .perform(
            post("/api/console/job-definitions")
                .contentType(APPLICATION_JSON)
                .content(createBody("123abc")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void rejects_jobCode_blank() throws Exception {
    mockMvc
        .perform(
            post("/api/console/job-definitions")
                .contentType(APPLICATION_JSON)
                .content(createBody("")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void accepts_valid_jobCode() throws Exception {
    // service mock 返回 null,responseFactory.success(null) 会被 controller 包成 SUCCESS;
    // 本测试只关心 validation 是否放行,不关心返回体的具体字段
    when(service.create(ArgumentMatchers.any())).thenReturn(null);

    mockMvc
        .perform(
            post("/api/console/job-definitions")
                .contentType(APPLICATION_JSON)
                .content(createBody("valid_job_01")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    verify(service).create(ArgumentMatchers.any());
  }

  @Test
  void rejects_clone_newJobCode_with_space() throws Exception {
    String body = "{\"tenantId\":\"ta\",\"newJobCode\":\"q q q\"}";
    mockMvc
        .perform(
            post("/api/console/job-definitions/1/clone")
                .contentType(APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
