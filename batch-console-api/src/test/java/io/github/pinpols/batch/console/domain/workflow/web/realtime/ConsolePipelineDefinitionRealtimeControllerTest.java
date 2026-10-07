package io.github.pinpols.batch.console.domain.workflow.web.realtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeSubscriptionPort;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@DisplayName("流水线定义实时事件流: 订阅接口开启异步流, 并按租户与频道注册订阅")
class ConsolePipelineDefinitionRealtimeControllerTest {

  private final ConsoleRealtimeSubscriptionPort realtimeEventHub =
      mock(ConsoleRealtimeSubscriptionPort.class);
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

    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsolePipelineDefinitionRealtimeController(realtimeEventHub, tenantGuard))
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  @Test
  @DisplayName("请求流水线定义事件流时, 订阅按租户与频道标识注册且异步响应已启动")
  void shouldExposeDomainRealtimeStreamOnPipelineDefinitionsController() throws Exception {
    mockMvc
        .perform(get("/api/console/pipeline-definitions/events").param("tenantId", "t1"))
        .andExpect(request().asyncStarted())
        .andReturn();

    verify(realtimeEventHub).subscribe("t1", "pipeline-definitions", null, null, null);
  }
}
