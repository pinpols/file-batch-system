package io.github.pinpols.batch.console.domain.job.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.application.ops.response.ConsoleInstanceActionResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsolePartitionActionResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsoleRetryFailedPartitionsResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** P2: ConsoleInstanceController cancel/terminate + 分区 cancel/retry 透传到 proxy。 */
class ConsoleInstanceControllerTest {

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
    mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleInstanceController(proxy, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  @Test
  void cancelShouldUseCancelAction() throws Exception {
    when(proxy.instanceAction(3L, "ta", "cancel"))
        .thenReturn(new ConsoleInstanceActionResponse(3L, null, "cancelled", null));
    mockMvc
        .perform(post("/api/console/instances/3/cancel").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).instanceAction(3L, "ta", "cancel");
  }

  @Test
  void terminateShouldUseTerminateAction() throws Exception {
    when(proxy.instanceAction(3L, "ta", "terminate"))
        .thenReturn(new ConsoleInstanceActionResponse(3L, null, "terminated", null));
    mockMvc
        .perform(post("/api/console/instances/3/terminate").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).instanceAction(3L, "ta", "terminate");
  }

  @Test
  void pauseShouldUsePauseAction() throws Exception {
    when(proxy.instanceAction(3L, "ta", "pause"))
        .thenReturn(new ConsoleInstanceActionResponse(3L, null, "PAUSED", null));
    mockMvc
        .perform(post("/api/console/instances/3/pause").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).instanceAction(3L, "ta", "pause");
  }

  @Test
  void resumeShouldUseResumeAction() throws Exception {
    when(proxy.instanceAction(3L, "ta", "resume"))
        .thenReturn(new ConsoleInstanceActionResponse(3L, null, "RUNNING", null));
    mockMvc
        .perform(post("/api/console/instances/3/resume").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).instanceAction(3L, "ta", "resume");
  }

  @Test
  void cancelPartitionAndRetryPartitionShouldRouteToPartitionAction() throws Exception {
    when(proxy.partitionAction(5L, "ta", "cancel"))
        .thenReturn(new ConsolePartitionActionResponse(5L, "ok"));
    when(proxy.partitionAction(5L, "ta", "retry"))
        .thenReturn(new ConsolePartitionActionResponse(5L, "ok"));
    mockMvc
        .perform(post("/api/console/instances/partitions/5/cancel").param("tenantId", "ta"))
        .andExpect(status().isOk());
    mockMvc
        .perform(post("/api/console/instances/partitions/5/retry").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(proxy).partitionAction(5L, "ta", "cancel");
    verify(proxy).partitionAction(5L, "ta", "retry");
  }

  @Test
  void retryFailedPartitionsShouldRouteToInstanceBatchAction() throws Exception {
    when(proxy.retryFailedPartitions(3L, "ta"))
        .thenReturn(new ConsoleRetryFailedPartitionsResponse(3L, null, 2, 2, 0, List.of()));

    mockMvc
        .perform(post("/api/console/instances/3/partitions/retry-failed").param("tenantId", "ta"))
        .andExpect(status().isOk());

    verify(proxy).retryFailedPartitions(3L, "ta");
  }
}
