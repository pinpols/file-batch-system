package com.example.batch.console.web.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeEventHub;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ConsoleOutboxRealtimeControllerTest {

    private final ConsoleRealtimeEventHub realtimeEventHub = org.mockito.Mockito.mock(ConsoleRealtimeEventHub.class);
    private final ConsoleTenantGuard tenantGuard = org.mockito.Mockito.mock(ConsoleTenantGuard.class);
    private final ConsoleRequestMetadataResolver requestMetadataResolver = org.mockito.Mockito.mock(ConsoleRequestMetadataResolver.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(
                new ConsoleResponseFactory(requestMetadataResolver),
                new BatchSecurityProperties());

        when(requestMetadataResolver.responseMeta()).thenReturn(new ResponseMeta("req-1", "trace-1", Instant.now()));
        when(tenantGuard.resolveTenant(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(realtimeEventHub.subscribe(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new SseEmitter());

        mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleOutboxRealtimeController(realtimeEventHub, tenantGuard))
                .setControllerAdvice(exceptionHandler)
                .build();
    }

    @Test
    void shouldExposeOutboxRetryRealtimeStream() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/console/stream/outbox-retries/events").param("tenantId", "t1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getAsyncResult()).isInstanceOf(SseEmitter.class);
        verify(tenantGuard).resolveTenant("t1");
        verify(realtimeEventHub).subscribe("t1", "outbox-retries", null, null, null);
    }

    @Test
    void shouldExposeOutboxDeliveryRealtimeStream() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/console/stream/outbox-deliveries/events").param("tenantId", "t2"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getAsyncResult()).isInstanceOf(SseEmitter.class);
        verify(tenantGuard).resolveTenant("t2");
        verify(realtimeEventHub).subscribe("t2", "outbox-deliveries", null, null, null);
    }
}
