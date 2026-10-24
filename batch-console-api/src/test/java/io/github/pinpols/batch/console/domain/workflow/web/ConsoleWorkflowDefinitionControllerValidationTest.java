package io.github.pinpols.batch.console.domain.workflow.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.workflow.application.ConsoleWorkflowTriggerService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDefinitionService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService;
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

/**
 * 守护 WorkflowDefinitionSaveRequest 的嵌套 @Valid 在 controller 生效。
 *
 * <p>历史缺陷:nodes / edges 列表元素的 @ValidResourceCode (nodeCode / fromNodeCode / toNodeCode)
 * 未被解析,异常数据可直通入库。本测试保证嵌套校验链不破。
 */
@DisplayName("工作流定义控制器入参校验: 编码与嵌套节点、连线非法时均拒绝请求")
class ConsoleWorkflowDefinitionControllerValidationTest {

  private final WorkflowDefinitionService service = mock(WorkflowDefinitionService.class);
  private final WorkflowDesignLockService lockService = mock(WorkflowDesignLockService.class);
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

    mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleWorkflowDefinitionController(
            service, responseFactory, lockService, mock(ConsoleWorkflowTriggerService.class)))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("触发结果核对缺少原幂等键时拒绝，不创建新的触发")
  void shouldRejectReceiptLookup_whenIdempotencyKeyIsMissing() throws Exception {
    mockMvc.perform(get("/api/console/workflow-definitions/23/trigger-status")
            .param("tenantId", "ta"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("工作流编码含空格时, 请求被校验拦截并返回参数错误, 不落库")
  void rejects_invalid_workflowCode() throws Exception {
    String body = "{\"tenantId\":\"ta\",\"workflowCode\":\"q q q\",\"workflowName\":\"wf\","
        + "\"workflowType\":\"DAG\"}";
    mockMvc
        .perform(post("/api/console/workflow-definitions")
            .contentType(APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verify(service, never()).create(ArgumentMatchers.any());
  }

  @Test
  @DisplayName("顶层编码合法但节点编码含空格时, 校验下钻并拒绝请求, 不落库")
  void rejects_nested_invalid_nodeCode() throws Exception {
    // 顶层 workflowCode 合法,但 nodes[0].nodeCode 含空格 → @Valid 应下钻 → 400
    String body = "{\"tenantId\":\"ta\",\"workflowCode\":\"wf_ok\",\"workflowName\":\"wf\","
        + "\"workflowType\":\"DAG\",\"nodes\":["
        + "{\"nodeCode\":\"bad node\",\"nodeType\":\"TASK\"}],\"edges\":[]}";
    mockMvc
        .perform(post("/api/console/workflow-definitions")
            .contentType(APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verify(service, never()).create(ArgumentMatchers.any());
  }

  @Test
  @DisplayName("连线起点编码含中文时, 校验下钻到连线元素并拒绝请求")
  void rejects_nested_invalid_edge_fromNodeCode() throws Exception {
    String body = """
        {
          "tenantId": "ta",
          "workflowCode": "wf_ok",
          "workflowName": "wf",
          "workflowType": "DAG",
          "nodes": [],
          "edges": [{"fromNodeCode": "中文", "toNodeCode": "end"}]
        }
        """.stripTrailing();
    mockMvc
        .perform(post("/api/console/workflow-definitions")
            .contentType(APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("编码与嵌套节点、连线全部合法时, 请求通过校验并创建成功")
  void shouldAcceptAllCodes_whenWorkflowFullyValid() throws Exception {
    when(service.create(ArgumentMatchers.any())).thenReturn(null);
    String body = """
        {
          "tenantId": "ta",
          "workflowCode": "wf_ok",
          "workflowName": "wf",
          "workflowType": "DAG",
          "nodes": [
            {"nodeCode": "start", "nodeType": "START"},
            {"nodeCode": "end", "nodeType": "END"}
          ],
          "edges": [{"fromNodeCode": "start", "toNodeCode": "end"}]
        }
        """.stripTrailing();
    mockMvc
        .perform(post("/api/console/workflow-definitions")
            .contentType(APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
    verify(service).create(ArgumentMatchers.any());
  }

  @Test
  @DisplayName("定时暂停只更新 Workflow schedule 开关，不调用定义启停")
  void patches_schedule_enabled_independently() throws Exception {
    mockMvc
        .perform(patch("/api/console/workflow-definitions/17/schedule-enabled")
            .contentType(APPLICATION_JSON)
            .content("{\"tenantId\":\"ta\",\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
    verify(service).toggleScheduleEnabled(17L, "ta", false);
    verify(service, never()).toggleEnabled(17L, "ta", false);
  }

  @Test
  @DisplayName("无效 Cron 调度表达式在保存前拒绝，不触碰 Workflow 定义")
  void rejects_invalid_schedule_expression() throws Exception {
    mockMvc
        .perform(put("/api/console/workflow-definitions/17/schedule")
            .contentType(APPLICATION_JSON)
            .content("""
                {"tenantId":"ta","expectedVersion":2,"scheduleType":"CRON",
                 "scheduleExpr":"not a cron","timezone":"Asia/Shanghai",
                 "triggerMode":"SCHEDULED"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verify(service, never())
        .updateSchedule(
            ArgumentMatchers.eq(17L), ArgumentMatchers.any(), ArgumentMatchers.anyString());
  }
}
