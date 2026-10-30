package io.github.pinpols.batch.console.domain.ops.web.realtime;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import io.github.pinpols.batch.console.application.ops.ConsoleOpsSummaryRealtimePort;
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

@DisplayName("运维概览实时流:订阅事件流并透传租户, 支持跳过首帧快照")
@ExtendWith(MockitoExtension.class)
class ConsoleOpsRealtimeControllerTest {

  @Mock
  private ConsoleOpsSummaryRealtimePort summaryRealtimeStream;

  @Mock
  private ConsoleRequestMetadataResolver requestMetadataResolver;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleApiExceptionHandler exceptionHandler = ConsoleApiExceptionHandler.forStandaloneTest(
        new ConsoleResponseFactory(requestMetadataResolver));

    when(summaryRealtimeStream.subscribe(anyString(), isNull(), anyBoolean()))
        .thenReturn(new SseEmitter());

    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleOpsRealtimeController(summaryRealtimeStream))
        .setControllerAdvice(exceptionHandler)
        .build();
  }

  @Test
  @DisplayName("事件流:订阅指定租户并默认开启首帧快照, 异步请求已启动")
  void shouldExposeSummaryRealtimeStream() throws Exception {
    mockMvc
        .perform(get("/api/console/ops/summary/events").param("tenantId", "t1"))
        .andExpect(request().asyncStarted())
        .andReturn();

    verify(summaryRealtimeStream).subscribe("t1", null, true);
  }

  @Test
  @DisplayName("跳过首帧:请求参数关闭快照时按关闭值订阅")
  void shouldAllowSkippingInitialSnapshot() throws Exception {
    mockMvc
        .perform(get("/api/console/ops/summary/events")
            .param("tenantId", "t1")
            .param("initialSnapshot", "false"))
        .andExpect(request().asyncStarted())
        .andReturn();

    verify(summaryRealtimeStream).subscribe("t1", null, false);
  }
}
