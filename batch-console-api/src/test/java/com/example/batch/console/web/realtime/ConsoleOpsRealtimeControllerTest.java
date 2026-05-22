package com.example.batch.console.web.realtime;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.infrastructure.realtime.ConsoleOpsSummaryRealtimeStream;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ConsoleOpsRealtimeControllerTest {

  private final ConsoleOpsSummaryRealtimeStream summaryRealtimeStream =
      mock(ConsoleOpsSummaryRealtimeStream.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleApiExceptionHandler exceptionHandler =
        new ConsoleApiExceptionHandler(
            new ConsoleResponseFactory(requestMetadataResolver), new BatchSecurityProperties());

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(summaryRealtimeStream.subscribe(anyString(), isNull(), anyBoolean()))
        .thenReturn(new SseEmitter());

    mockMvc =
        MockMvcBuilders.standaloneSetup(new ConsoleOpsRealtimeController(summaryRealtimeStream))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void shouldExposeSummaryRealtimeStream() throws Exception {
    mockMvc
        .perform(get("/api/console/ops/summary/events").param("tenantId", "t1"))
        .andExpect(request().asyncStarted())
        .andReturn();

    verify(summaryRealtimeStream).subscribe("t1", null, true);
  }

  @Test
  void shouldAllowSkippingInitialSnapshot() throws Exception {
    mockMvc
        .perform(
            get("/api/console/ops/summary/events")
                .param("tenantId", "t1")
                .param("initialSnapshot", "false"))
        .andExpect(request().asyncStarted())
        .andReturn();

    verify(summaryRealtimeStream).subscribe("t1", null, false);
  }
}
