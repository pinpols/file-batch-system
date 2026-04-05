package com.example.batch.console.infrastructure.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.console.application.ConsoleOpsApplicationService;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.response.ConsoleOpsSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ConsoleOpsSummaryRealtimeStreamTest {

    private final ConsoleOpsApplicationService opsApplicationService = org.mockito.Mockito.mock(ConsoleOpsApplicationService.class);
    private final ConsoleRealtimeEventHub realtimeEventHub = org.mockito.Mockito.mock(ConsoleRealtimeEventHub.class);
    private final ConsoleRealtimeRedisPublisher redisPublisher = org.mockito.Mockito.mock(ConsoleRealtimeRedisPublisher.class);
    private final ConsoleRealtimeCursorFactory cursorFactory = org.mockito.Mockito.mock(ConsoleRealtimeCursorFactory.class);
    private final ConsoleTenantGuard tenantGuard = org.mockito.Mockito.mock(ConsoleTenantGuard.class);
    private ConsoleOpsSummaryRealtimeStream stream;

    @BeforeEach
    void setUp() {
        stream = new ConsoleOpsSummaryRealtimeStream(opsApplicationService, realtimeEventHub, redisPublisher, cursorFactory, tenantGuard);
        when(tenantGuard.resolveTenant(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cursorFactory.nextCursor()).thenReturn("cursor-1");
        when(realtimeEventHub.subscribe(anyString(), anyString(), isNull(), isNull(), isNull())).thenReturn(new SseEmitter());
    }

    @Test
    void shouldSkipInitialSnapshotWhenDisabled() {
        SseEmitter emitter = stream.subscribe("t1", null, false);

        assertThat(emitter).isNotNull();
        verify(realtimeEventHub).subscribe("t1", "ops-summary", null, null, null);
        verify(realtimeEventHub, never()).publish(org.mockito.ArgumentMatchers.any());
        verify(opsApplicationService, never()).summary(anyString());
    }

    @Test
    void shouldThrottleRepeatedRefreshRequests() {
        when(opsApplicationService.summary("t1")).thenReturn(new ConsoleOpsSummaryResponse(
                "t1",
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8,
                9,
                10,
                11
        ));

        stream.publishRefresh("t1");
        stream.publishRefresh("t1");
        try {
            Thread.sleep(500L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }

        verify(opsApplicationService).summary("t1");
        verify(realtimeEventHub).publish(org.mockito.ArgumentMatchers.any());
        verify(redisPublisher).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldForceInitialSnapshotWhenSubscribed() {
        when(opsApplicationService.summary("t1")).thenReturn(new ConsoleOpsSummaryResponse(
                "t1",
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8,
                9,
                10,
                11
        ));

        stream.subscribe("t1", null, true);

        verify(opsApplicationService).summary("t1");
        verify(realtimeEventHub).publish(org.mockito.ArgumentMatchers.any());
        verify(redisPublisher).publish(org.mockito.ArgumentMatchers.any());
    }
}
