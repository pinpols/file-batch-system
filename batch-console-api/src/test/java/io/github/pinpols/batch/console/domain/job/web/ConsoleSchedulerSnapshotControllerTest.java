package io.github.pinpols.batch.console.domain.job.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleOrchestratorProxyService;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleSchedulerSnapshotResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleSchedulerSnapshotControllerTest {

  private final ConsoleOrchestratorProxyService orchestratorProxyService =
      mock(ConsoleOrchestratorProxyService.class);
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

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleSchedulerSnapshotController(orchestratorProxyService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldReturn200WhenGetLiveSnapshot() throws Exception {
    when(orchestratorProxyService.schedulerSnapshot(anyString()))
        .thenReturn(
            new ConsoleSchedulerSnapshotResponse(
                BatchDateTimeSupport.utcNow(), "t1", List.of(), List.of(), List.of()));

    mockMvc
        .perform(get("/api/console/scheduler/snapshot").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.tenantId").value("t1"));
  }

  @Test
  void shouldReturn200WhenGetSnapshotHistory() throws Exception {
    when(orchestratorProxyService.schedulerSnapshotHistory(anyString(), anyInt()))
        .thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/console/scheduler/snapshot/history")
                .param("tenantId", "t1")
                .param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
  }
}
