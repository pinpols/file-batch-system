package com.example.batch.console.web.realtime;

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
import static org.mockito.Mockito.mock;

class ConsoleJobInstanceRealtimeControllerTest {

    private final ConsoleRealtimeEventHub realtimeEventHub = mock(ConsoleRealtimeEventHub.class);
    private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
    private final ConsoleRequestMetadataResolver requestMetadataResolver = mock(ConsoleRequestMetadataResolver.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(
                new ConsoleResponseFactory(requestMetadataResolver),
                new BatchSecurityProperties());

        when(requestMetadataResolver.responseMeta()).thenReturn(new ResponseMeta("req-1", "trace-1", Instant.now()));
        when(tenantGuard.resolveTenant(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(realtimeEventHub.subscribe(anyString(), anyString(), any(), any(), any()))
                .thenReturn(new SseEmitter());

        mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleJobInstanceRealtimeController(realtimeEventHub, tenantGuard))
                .setControllerAdvice(exceptionHandler)
                .build();
    }

    @Test
    void shouldExposeJobInstanceRealtimeStream() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/console/stream/job-instances/events").param("tenantId", "t1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        verify(tenantGuard).resolveTenant("t1");
        verify(realtimeEventHub).subscribe("t1", "job-instances", null, null, null);
    }
}
