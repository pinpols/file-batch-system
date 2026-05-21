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
import com.example.batch.console.application.workflow.ConsoleWorkflowDefinitionApplicationService;
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
 * 守护 WorkflowDefinitionSaveRequest 的嵌套 @Valid 在 controller 生效。
 *
 * <p>历史缺陷:nodes / edges 列表元素的 @ValidResourceCode (nodeCode / fromNodeCode / toNodeCode)
 * 未被解析,脏数据可直通入库。本测试保证嵌套校验链不破。
 */
class ConsoleWorkflowDefinitionControllerValidationTest {

  private final ConsoleWorkflowDefinitionApplicationService service =
      mock(ConsoleWorkflowDefinitionApplicationService.class);
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
                new ConsoleWorkflowDefinitionController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void rejects_invalid_workflowCode() throws Exception {
    String body =
        "{\"tenantId\":\"ta\",\"workflowCode\":\"q q q\",\"workflowName\":\"wf\","
            + "\"workflowType\":\"DAG\"}";
    mockMvc
        .perform(
            post("/api/console/workflow-definitions").contentType(APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verify(service, never()).create(ArgumentMatchers.any());
  }

  @Test
  void rejects_nested_invalid_nodeCode() throws Exception {
    // 顶层 workflowCode 合法,但 nodes[0].nodeCode 含空格 → @Valid 应下钻 → 400
    String body =
        "{\"tenantId\":\"ta\",\"workflowCode\":\"wf_ok\",\"workflowName\":\"wf\","
            + "\"workflowType\":\"DAG\",\"nodes\":["
            + "{\"nodeCode\":\"bad node\",\"nodeType\":\"TASK\"}],\"edges\":[]}";
    mockMvc
        .perform(
            post("/api/console/workflow-definitions").contentType(APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verify(service, never()).create(ArgumentMatchers.any());
  }

  @Test
  void rejects_nested_invalid_edge_fromNodeCode() throws Exception {
    String body =
        "{\"tenantId\":\"ta\",\"workflowCode\":\"wf_ok\",\"workflowName\":\"wf\","
            + "\"workflowType\":\"DAG\",\"nodes\":[],"
            + "\"edges\":[{\"fromNodeCode\":\"中文\",\"toNodeCode\":\"end\"}]}";
    mockMvc
        .perform(
            post("/api/console/workflow-definitions").contentType(APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void acceptsAllValidCodes() throws Exception {
    when(service.create(ArgumentMatchers.any())).thenReturn(null);
    String body =
        "{\"tenantId\":\"ta\",\"workflowCode\":\"wf_ok\",\"workflowName\":\"wf\","
            + "\"workflowType\":\"DAG\","
            + "\"nodes\":[{\"nodeCode\":\"start\",\"nodeType\":\"START\"},"
            + "{\"nodeCode\":\"end\",\"nodeType\":\"END\"}],"
            + "\"edges\":[{\"fromNodeCode\":\"start\",\"toNodeCode\":\"end\"}]}";
    mockMvc
        .perform(
            post("/api/console/workflow-definitions").contentType(APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
    verify(service).create(ArgumentMatchers.any());
  }
}
