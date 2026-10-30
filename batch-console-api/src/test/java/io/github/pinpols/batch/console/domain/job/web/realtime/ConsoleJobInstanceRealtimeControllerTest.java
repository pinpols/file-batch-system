package io.github.pinpols.batch.console.domain.job.web.realtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeSubscriptionPort;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@DisplayName("作业实例实时控制器: 服务器推送流的建立与订阅委托")
@ExtendWith(MockitoExtension.class)
class ConsoleJobInstanceRealtimeControllerTest {

  @Mock
  private ConsoleRealtimeSubscriptionPort realtimeEventHub;

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @Mock
  private ConsoleRequestMetadataResolver requestMetadataResolver;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleApiExceptionHandler exceptionHandler = ConsoleApiExceptionHandler.forStandaloneTest(
        new ConsoleResponseFactory(requestMetadataResolver));

    when(tenantGuard.resolveTenant(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(realtimeEventHub.subscribe(anyString(), anyString(), any(), any(), any()))
        .thenReturn(new SseEmitter());

    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleJobInstanceRealtimeController(realtimeEventHub, tenantGuard))
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  @Test
  @DisplayName("建立实例事件推送流, 按租户解析后委托订阅")
  void shouldExposeJobInstanceRealtimeStream() throws Exception {
    mockMvc
        .perform(get("/api/console/stream/job-instances/events").param("tenantId", "t1"))
        .andExpect(request().asyncStarted())
        .andReturn();

    verify(tenantGuard).resolveTenant("t1");
    verify(realtimeEventHub).subscribe("t1", "job-instances", null, null, null);
  }
}
