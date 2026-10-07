package io.github.pinpols.batch.console.web;

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
import io.github.pinpols.batch.console.application.config.ConsoleQuotaPolicyApplicationService;
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

/** 守护 ConsoleQuotaPolicyController 的 QuotaPolicySaveRequest.policyCode 走 @ValidResourceCode 拦截。 */
@DisplayName("配额策略控制器入参校验: 策略编码非法时拒绝请求")
class ConsoleQuotaPolicyControllerValidationTest {

  private final ConsoleQuotaPolicyApplicationService service =
      mock(ConsoleQuotaPolicyApplicationService.class);
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
            new ConsoleQuotaPolicyController(service, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  private String body(String policyCode) {
    return "{" + "\"tenantId\":\"ta\"," + "\"policyCode\":\"" + policyCode + "\"" + "}";
  }

  @Test
  @DisplayName("策略编码含空格时, 请求被校验拦截并返回参数错误, 不落库")
  void rejects_policyCode_with_space() throws Exception {
    mockMvc
        .perform(post("/api/console/quota-policies")
            .contentType(APPLICATION_JSON)
            .content(body("q q q")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verify(service, never()).create(ArgumentMatchers.any());
  }

  @Test
  @DisplayName("策略编码含中文时, 请求被校验拦截并返回参数错误")
  void rejects_policyCode_chinese() throws Exception {
    mockMvc
        .perform(
            post("/api/console/quota-policies").contentType(APPLICATION_JSON).content(body("配额")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("策略编码以数字开头时, 请求被校验拦截并返回参数错误")
  void rejects_policyCode_starts_with_digit() throws Exception {
    mockMvc
        .perform(
            post("/api/console/quota-policies").contentType(APPLICATION_JSON).content(body("1q")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("策略编码为空时, 请求被校验拦截并返回参数错误")
  void rejects_policyCode_blank() throws Exception {
    mockMvc
        .perform(
            post("/api/console/quota-policies").contentType(APPLICATION_JSON).content(body("")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("策略编码合法时, 请求通过校验并创建成功")
  void accepts_valid_policyCode() throws Exception {
    when(service.create(ArgumentMatchers.any())).thenReturn(null);
    mockMvc
        .perform(post("/api/console/quota-policies")
            .contentType(APPLICATION_JSON)
            .content(body("policy_ok_01")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
    verify(service).create(ArgumentMatchers.any());
  }
}
