package com.example.batch.console.domain.job.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.ops.application.ConsoleOrchestratorProxyService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** P2: ConsoleInstanceController cancel/terminate + 分区 cancel/retry 透传到 proxy。 */
class ConsoleInstanceControllerTest {

  private final ConsoleOrchestratorProxyService proxy = mock(ConsoleOrchestratorProxyService.class);
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
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ConsoleInstanceController(proxy, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void cancelShouldUseCancelAction() throws Exception {
    when(proxy.instanceAction(3L, "ta", "cancel")).thenReturn(Map.of("status", "cancelled"));
    mockMvc
        .perform(post("/api/console/instances/3/cancel").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).instanceAction(3L, "ta", "cancel");
  }

  @Test
  void terminateShouldUseTerminateAction() throws Exception {
    when(proxy.instanceAction(3L, "ta", "terminate")).thenReturn(Map.of("status", "terminated"));
    mockMvc
        .perform(post("/api/console/instances/3/terminate").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).instanceAction(3L, "ta", "terminate");
  }

  @Test
  void cancelPartitionAndRetryPartitionShouldRouteToPartitionAction() throws Exception {
    when(proxy.partitionAction(5L, "ta", "cancel")).thenReturn(Map.of("status", "ok"));
    when(proxy.partitionAction(5L, "ta", "retry")).thenReturn(Map.of("status", "ok"));
    mockMvc
        .perform(post("/api/console/instances/partitions/5/cancel").param("tenantId", "ta"))
        .andExpect(status().isOk());
    mockMvc
        .perform(post("/api/console/instances/partitions/5/retry").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).partitionAction(5L, "ta", "cancel");
    verify(proxy).partitionAction(5L, "ta", "retry");
  }
}
