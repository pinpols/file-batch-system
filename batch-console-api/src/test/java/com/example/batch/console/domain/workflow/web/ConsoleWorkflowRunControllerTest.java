package com.example.batch.console.domain.workflow.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.application.ops.ConsoleOrchestratorProxyService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** P2: ConsoleWorkflowRunController cancel/terminate/skip-node 透传到 proxy。 */
class ConsoleWorkflowRunControllerTest {

  private final ConsoleOrchestratorProxyService proxy = mock(ConsoleOrchestratorProxyService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ConsoleWorkflowRunController(proxy, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void cancelShouldDelegate() throws Exception {
    when(proxy.workflowRunAction(3L, "ta", "cancel")).thenReturn(Map.of("status", "ok"));
    mockMvc
        .perform(post("/api/console/workflow-runs/3/cancel").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).workflowRunAction(3L, "ta", "cancel");
  }

  @Test
  void terminateShouldDelegate() throws Exception {
    when(proxy.workflowRunAction(3L, "ta", "terminate")).thenReturn(Map.of("status", "ok"));
    mockMvc
        .perform(post("/api/console/workflow-runs/3/terminate").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).workflowRunAction(3L, "ta", "terminate");
  }

  @Test
  void skipNodeShouldPassNodeCode() throws Exception {
    when(proxy.workflowRunSkipNode(3L, "ta", "NODE_A")).thenReturn(Map.of("status", "skipped"));
    mockMvc
        .perform(
            post("/api/console/workflow-runs/3/skip-node")
                .param("tenantId", "ta")
                .param("nodeCode", "NODE_A"))
        .andExpect(status().isOk());
    verify(proxy).workflowRunSkipNode(3L, "ta", "NODE_A");
  }
}
