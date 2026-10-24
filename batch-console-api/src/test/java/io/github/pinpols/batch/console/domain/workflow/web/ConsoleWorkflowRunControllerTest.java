package io.github.pinpols.batch.console.domain.workflow.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.application.ops.response.ConsoleWorkflowRunActionResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsoleWorkflowRunSkipNodeResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** P2: ConsoleWorkflowRunController cancel/terminate/skip-node 透传到 proxy。 */
@DisplayName("工作流运行控制器: 取消、终止、暂停、恢复与跳过节点动作透传到编排端口")
class ConsoleWorkflowRunControllerTest {

  private final ConsoleOrchestratorPort proxy = mock(ConsoleOrchestratorPort.class);
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
    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleWorkflowRunController(proxy, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("取消工作流运行时, 运行标识、租户与动作标识透传到编排端口")
  void shouldDelegateCancelAction_whenCancellingRun() throws Exception {
    when(proxy.workflowRunAction(3L, "ta", "cancel"))
        .thenReturn(new ConsoleWorkflowRunActionResponse(3L, "ok"));
    mockMvc
        .perform(post("/api/console/workflow-runs/3/cancel").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).workflowRunAction(3L, "ta", "cancel");
  }

  @Test
  @DisplayName("终止工作流运行时, 运行标识、租户与动作标识透传到编排端口")
  void shouldDelegateTerminateAction_whenTerminatingRun() throws Exception {
    when(proxy.workflowRunAction(3L, "ta", "terminate"))
        .thenReturn(new ConsoleWorkflowRunActionResponse(3L, "ok"));
    mockMvc
        .perform(post("/api/console/workflow-runs/3/terminate").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).workflowRunAction(3L, "ta", "terminate");
  }

  @Test
  @DisplayName("暂停工作流运行时, 运行标识、租户与动作标识透传到编排端口")
  void shouldDelegatePauseAction_whenPausingRun() throws Exception {
    when(proxy.workflowRunAction(3L, "ta", "pause"))
        .thenReturn(new ConsoleWorkflowRunActionResponse(3L, "PAUSED"));
    mockMvc
        .perform(post("/api/console/workflow-runs/3/pause").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).workflowRunAction(3L, "ta", "pause");
  }

  @Test
  @DisplayName("恢复工作流运行时, 运行标识、租户与动作标识透传到编排端口")
  void shouldDelegateResumeAction_whenResumingRun() throws Exception {
    when(proxy.workflowRunAction(3L, "ta", "resume"))
        .thenReturn(new ConsoleWorkflowRunActionResponse(3L, "RUNNING"));
    mockMvc
        .perform(post("/api/console/workflow-runs/3/resume").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).workflowRunAction(3L, "ta", "resume");
  }

  @Test
  @DisplayName("跳过指定节点时, 运行标识、租户与节点编码透传到编排端口")
  void shouldPassNodeCode_whenSkippingNode() throws Exception {
    ConsolePrincipal principal = new ConsolePrincipal("operator-1", "ta", Set.of("ROLE_ADMIN"));
    List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal, null, authorities);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    when(proxy.workflowRunSkipNode(3L, "ta", "NODE_A", "operator-1", "manual incident recovery"))
        .thenReturn(new ConsoleWorkflowRunSkipNodeResponse(3L, "NODE_A", "SKIPPED"));
    mockMvc
        .perform(post("/api/console/workflow-runs/3/skip-node")
            .param("tenantId", "ta")
            .param("nodeCode", "NODE_A")
            .param("reason", "manual incident recovery")
            .param("operatorId", "untrusted-client-value"))
        .andExpect(status().isOk());
    verify(proxy).workflowRunSkipNode(3L, "ta", "NODE_A", "operator-1", "manual incident recovery");
  }

  @Test
  @DisplayName("跳过节点必须提供人工处置理由")
  void shouldRejectSkipNodeWithoutReason() throws Exception {
    mockMvc
        .perform(post("/api/console/workflow-runs/3/skip-node")
            .param("tenantId", "ta")
            .param("nodeCode", "NODE_A"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("跳过节点拒绝空白理由")
  void shouldRejectBlankSkipReason() throws Exception {
    mockMvc
        .perform(post("/api/console/workflow-runs/3/skip-node")
            .param("tenantId", "ta")
            .param("nodeCode", "NODE_A")
            .param("reason", "   "))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("跳过节点理由不能超过 1024 个字符")
  void shouldRejectSkipReasonLongerThan1024Characters() throws Exception {
    String tooLongReason = "x".repeat(1025);
    mockMvc
        .perform(post("/api/console/workflow-runs/3/skip-node")
            .param("tenantId", "ta")
            .param("nodeCode", "NODE_A")
            .param("reason", tooLongReason))
        .andExpect(status().isBadRequest());
  }
}
