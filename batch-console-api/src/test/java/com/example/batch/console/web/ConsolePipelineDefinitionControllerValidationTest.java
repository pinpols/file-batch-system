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
import com.example.batch.console.application.workflow.ConsolePipelineDefinitionApplicationService;
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
 * 守护 ConsolePipelineDefinitionController 的 PipelineDefinitionSaveRequest.jobCode + 嵌套 steps
 * 的 @Valid 拦截。
 */
class ConsolePipelineDefinitionControllerValidationTest {

  private final ConsolePipelineDefinitionApplicationService service =
      mock(ConsolePipelineDefinitionApplicationService.class);
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
        MockMvcBuilders.standaloneSetup(
                new ConsolePipelineDefinitionController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  private String body(String jobCode) {
    return "{"
        + "\"tenantId\":\"ta\","
        + "\"jobCode\":\""
        + jobCode
        + "\","
        + "\"pipelineName\":\"pl\","
        + "\"pipelineType\":\"IMPORT\""
        + "}";
  }

  @Test
  void rejects_jobCode_with_space() throws Exception {
    mockMvc
        .perform(
            post("/api/console/pipeline-definitions")
                .contentType(APPLICATION_JSON)
                .content(body("q q q")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verify(service, never()).create(ArgumentMatchers.any());
  }

  @Test
  void rejects_jobCode_chinese() throws Exception {
    mockMvc
        .perform(
            post("/api/console/pipeline-definitions")
                .contentType(APPLICATION_JSON)
                .content(body("中文")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void rejects_jobCode_starts_with_digit() throws Exception {
    mockMvc
        .perform(
            post("/api/console/pipeline-definitions")
                .contentType(APPLICATION_JSON)
                .content(body("1pipeline")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void rejects_jobCode_blank() throws Exception {
    mockMvc
        .perform(
            post("/api/console/pipeline-definitions")
                .contentType(APPLICATION_JSON)
                .content(body("")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void rejects_nested_step_with_blank_stepCode() throws Exception {
    // 顶层 jobCode 合法,但 steps[0].stepCode 为空 → @Valid 应下钻 → 400
    String body =
        "{\"tenantId\":\"ta\",\"jobCode\":\"pl_ok\",\"pipelineName\":\"pl\","
            + "\"pipelineType\":\"IMPORT\","
            + "\"steps\":[{\"stepCode\":\"\",\"stepName\":\"s\",\"stageCode\":\"st\","
            + "\"implCode\":\"impl\"}]}";
    mockMvc
        .perform(
            post("/api/console/pipeline-definitions").contentType(APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verify(service, never()).create(ArgumentMatchers.any());
  }

  @Test
  void rejects_invalid_pipelineType() throws Exception {
    String body =
        "{\"tenantId\":\"ta\",\"jobCode\":\"pl_ok\",\"pipelineName\":\"pl\","
            + "\"pipelineType\":\"UNKNOWN\"}";
    mockMvc
        .perform(
            post("/api/console/pipeline-definitions").contentType(APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void accepts_valid_request_with_nested_steps() throws Exception {
    when(service.create(ArgumentMatchers.any())).thenReturn(null);
    String body =
        "{\"tenantId\":\"ta\",\"jobCode\":\"pl_ok\",\"pipelineName\":\"pl\","
            + "\"pipelineType\":\"IMPORT\","
            + "\"steps\":[{\"stepCode\":\"s1\",\"stepName\":\"step1\",\"stageCode\":\"stage1\","
            + "\"implCode\":\"impl1\"}]}";
    mockMvc
        .perform(
            post("/api/console/pipeline-definitions").contentType(APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
    verify(service).create(ArgumentMatchers.any());
  }
}
