package com.example.batch.console.domain.workflow.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.rbac.support.ConsolePrincipal;
import com.example.batch.console.domain.workflow.application.ConsoleWorkflowDefinitionApplicationService;
import com.example.batch.console.domain.workflow.application.WorkflowDesignLockService;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionFullUpdateRequest;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * 单测 PUT /api/console/workflow-definitions/{id}/full(BE Spike,workflow-dag-designer)。
 *
 * <p>覆盖 3 case:成功、锁不归属(service 抛 CONFLICT)、嵌套 @Valid 校验失败。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConsoleWorkflowFullUpdate Controller")
class ConsoleWorkflowFullUpdateControllerTest {

  @Mock private ConsoleWorkflowDefinitionApplicationService service;
  @Mock private WorkflowDesignLockService lockService;
  @Mock private ConsoleRequestMetadataResolver requestMetadataResolver;

  private MockMvc mockMvc;

  private static final String USER_ALICE = "alice";

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleWorkflowDefinitionController(service, responseFactory, lockService))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();

    // SecurityContext 注入 ConsolePrincipal,controller currentUsername() 读取
    ConsolePrincipal principal =
        new ConsolePrincipal(USER_ALICE, "ta", Set.of("ROLE_TENANT_ADMIN"));
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN")));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("成功:有效 body + 持锁人 → 200 SUCCESS")
  void shouldSucceed_whenValidAndLockHeld() throws Exception {
    when(service.fullUpdate(
            eq(42L), any(WorkflowDefinitionFullUpdateRequest.class), eq(USER_ALICE)))
        .thenReturn(null);

    String body = validBody();
    mockMvc
        .perform(
            put("/api/console/workflow-definitions/42/full")
                .contentType(APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
    verify(service).fullUpdate(eq(42L), any(), eq(USER_ALICE));
  }

  @Test
  @DisplayName("锁不归属当前 user:service 抛 CONFLICT → 409 带 lockedBy")
  void shouldReturnConflict_whenLockHeldByOther() throws Exception {
    when(service.fullUpdate(eq(42L), any(), eq(USER_ALICE)))
        .thenThrow(
            BizException.of(
                ResultCode.CONFLICT, "error.workflow_design_lock.held_by_other", "bob"));

    mockMvc
        .perform(
            put("/api/console/workflow-definitions/42/full")
                .contentType(APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));
  }

  @Test
  @DisplayName("嵌套 @Valid 失败:nodeCode 含空格 → 400 VALIDATION_ERROR,不调 service")
  void shouldRejectValidation_whenNestedNodeCodeInvalid() throws Exception {
    String body =
        "{\"definition\":{"
            + "\"tenantId\":\"ta\",\"workflowCode\":\"wf_ok\",\"workflowName\":\"wf\","
            + "\"workflowType\":\"DAG\","
            + "\"nodes\":[{\"nodeCode\":\"bad code\",\"nodeType\":\"TASK\"}],\"edges\":[]"
            + "},\"expectedVersion\":1}";
    mockMvc
        .perform(
            put("/api/console/workflow-definitions/42/full")
                .contentType(APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verify(service, never()).fullUpdate(any(Long.class), any(), any());
  }

  private static String validBody() {
    return "{\"definition\":{"
        + "\"tenantId\":\"ta\",\"workflowCode\":\"wf_ok\",\"workflowName\":\"wf\","
        + "\"workflowType\":\"DAG\","
        + "\"nodes\":[{\"nodeCode\":\"start\",\"nodeType\":\"START\"},"
        + "{\"nodeCode\":\"end\",\"nodeType\":\"END\"}],"
        + "\"edges\":[{\"fromNodeCode\":\"start\",\"toNodeCode\":\"end\"}]"
        + "},\"expectedVersion\":1}";
  }
}
