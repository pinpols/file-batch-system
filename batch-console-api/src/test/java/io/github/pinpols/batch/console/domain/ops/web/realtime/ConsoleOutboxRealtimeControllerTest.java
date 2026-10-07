package io.github.pinpols.batch.console.domain.ops.web.realtime;

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

@DisplayName("投递重试与投递结果实时流:按通道订阅事件流, 并先解析租户")
class ConsoleOutboxRealtimeControllerTest {

  private final ConsoleRealtimeSubscriptionPort realtimeEventHub =
      mock(ConsoleRealtimeSubscriptionPort.class);
  private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleApiExceptionHandler exceptionHandler = ConsoleApiExceptionHandler.forStandaloneTest(
        new ConsoleResponseFactory(requestMetadataResolver));

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(tenantGuard.resolveTenant(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(realtimeEventHub.subscribe(anyString(), anyString(), any(), any(), any()))
        .thenReturn(new SseEmitter());

    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleOutboxRealtimeController(realtimeEventHub, tenantGuard))
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  @Test
  @DisplayName("重试流:解析租户后按重试通道订阅, 异步请求已启动")
  void shouldExposeOutboxRetryRealtimeStream() throws Exception {
    mockMvc
        .perform(get("/api/console/stream/outbox-retries/events").param("tenantId", "t1"))
        .andExpect(request().asyncStarted())
        .andReturn();

    verify(tenantGuard).resolveTenant("t1");
    verify(realtimeEventHub).subscribe("t1", "outbox-retries", null, null, null);
  }

  @Test
  @DisplayName("投递流:解析租户后按投递通道订阅, 异步请求已启动")
  void shouldExposeOutboxDeliveryRealtimeStream() throws Exception {
    mockMvc
        .perform(get("/api/console/stream/outbox-deliveries/events").param("tenantId", "t2"))
        .andExpect(request().asyncStarted())
        .andReturn();

    verify(tenantGuard).resolveTenant("t2");
    verify(realtimeEventHub).subscribe("t2", "outbox-deliveries", null, null, null);
  }
}
