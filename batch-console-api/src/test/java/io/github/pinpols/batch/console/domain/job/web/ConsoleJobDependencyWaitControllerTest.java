package io.github.pinpols.batch.console.domain.job.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Console 作业依赖等待的租户查询边界")
class ConsoleJobDependencyWaitControllerTest {

  private final ConsoleOrchestratorPort orchestrator = mock(ConsoleOrchestratorPort.class);
  private final ConsoleRequestMetadataResolver metadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(metadataResolver);
    when(metadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleJobDependencyWaitController(orchestrator, responseFactory))
        .setControllerAdvice(ConsoleApiExceptionHandler.forStandaloneTest(responseFactory))
        .build();
  }

  @DisplayName("租户查询等待且不暴露启动载荷")
  @Test
  void shouldListTenantScopedWaitsWithoutExposingLaunchPayload() throws Exception {
    when(orchestrator.listJobDependencyWaits("tenant-a", "WAITING", "JOB_B", 50))
        .thenReturn(List.of());

    mockMvc
        .perform(get("/api/console/job-dependency-waits")
            .param("tenantId", "tenant-a")
            .param("waitStatus", "WAITING")
            .param("jobCode", "JOB_B")
            .param("limit", "50"))
        .andExpect(status().isOk());

    verify(orchestrator).listJobDependencyWaits("tenant-a", "WAITING", "JOB_B", 50);
  }
}
