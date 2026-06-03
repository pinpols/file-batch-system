package com.example.batch.console.domain.workflow.web.realtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.observability.realtime.ConsoleRealtimeEventHub;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ConsolePipelineDefinitionRealtimeControllerTest {

  private final ConsoleRealtimeEventHub realtimeEventHub = mock(ConsoleRealtimeEventHub.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(tenantGuard.resolveTenant(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(realtimeEventHub.subscribe(anyString(), anyString(), any(), any(), any()))
        .thenReturn(new SseEmitter());

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsolePipelineDefinitionRealtimeController(realtimeEventHub, tenantGuard))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void shouldExposeDomainRealtimeStreamOnPipelineDefinitionsController() throws Exception {
    mockMvc
        .perform(get("/api/console/pipeline-definitions/events").param("tenantId", "t1"))
        .andExpect(request().asyncStarted())
        .andReturn();

    verify(realtimeEventHub).subscribe("t1", "pipeline-definitions", null, null, null);
  }
}
