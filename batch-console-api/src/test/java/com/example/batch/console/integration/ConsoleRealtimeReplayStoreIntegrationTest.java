package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.config.ConsoleRealtimeProperties;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeMetrics;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeReplayStore;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeReplayStore.ReplayBatch;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeStreamEnvelope;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;

/**
 * 集成测试：验证 ConsoleRealtimeReplayStore 的 append/replay 操作使用真实 Redis 容器执行。
 */
@SpringBootTest(
        classes = ConsoleRealtimeReplayStoreIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "batch.console.realtime.replay-max-entries=100",
                "batch.console.realtime.replay-ttl=1h"
        })
class ConsoleRealtimeReplayStoreIntegrationTest extends AbstractIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(ConsoleRealtimeProperties.class)
    @Import(ConsoleRealtimeReplayStore.class)
    static class TestApplication {
    }

    @MockitoBean
    private ConsoleRealtimeMetrics realtimeMetrics;

    @Autowired
    private ConsoleRealtimeReplayStore replayStore;

    @Test
    void appendAndReplayReturnsEventsAfterCursor() {
        String tenantId = "t-replay-" + System.nanoTime();
        String stream = "job-instance";

        ConsoleRealtimeStreamEnvelope e1 = envelope(tenantId, stream, "cursor-1", "JOB_STATUS");
        ConsoleRealtimeStreamEnvelope e2 = envelope(tenantId, stream, "cursor-2", "JOB_STATUS");
        ConsoleRealtimeStreamEnvelope e3 = envelope(tenantId, stream, "cursor-3", "JOB_STATUS");

        replayStore.append(e1);
        replayStore.append(e2);
        replayStore.append(e3);

        ReplayBatch batch = replayStore.replay(tenantId, stream, "cursor-1", null);

        assertThat(batch.cursorFound()).isTrue();
        assertThat(batch.events()).hasSize(2);
        assertThat(batch.events().get(0).cursor()).isEqualTo("cursor-2");
        assertThat(batch.events().get(1).cursor()).isEqualTo("cursor-3");
    }

    @Test
    void replayWithUnknownCursorReturnsAllEventsAndCursorNotFound() {
        String tenantId = "t-nocursor-" + System.nanoTime();
        String stream = "ops";

        replayStore.append(envelope(tenantId, stream, "c-10", "SUMMARY"));
        replayStore.append(envelope(tenantId, stream, "c-11", "SUMMARY"));

        ReplayBatch batch = replayStore.replay(tenantId, stream, "unknown-cursor", null);

        assertThat(batch.cursorFound()).isFalse();
        assertThat(batch.events()).hasSize(2);
    }

    @Test
    void replayWithEventTypeFilterReturnsOnlyMatchingEvents() {
        String tenantId = "t-filter-" + System.nanoTime();
        String stream = "pipeline";

        replayStore.append(envelope(tenantId, stream, "c-a", "NODE_STATUS"));
        replayStore.append(envelope(tenantId, stream, "c-b", "SUMMARY_REFRESH"));
        replayStore.append(envelope(tenantId, stream, "c-c", "NODE_STATUS"));

        ReplayBatch batch = replayStore.replay(tenantId, stream, "NONEXISTENT", "NODE_STATUS");

        List<ConsoleRealtimeStreamEnvelope> nodeEvents = batch.events();
        assertThat(nodeEvents).hasSize(2);
        assertThat(nodeEvents).allMatch(e -> "NODE_STATUS".equals(e.eventType()));
    }

    @Test
    void replayWithBlankParamsReturnsCursorFoundTrueWithEmptyEvents() {
        ReplayBatch blankTenant = replayStore.replay("", "stream", "cursor", null);
        ReplayBatch blankStream = replayStore.replay("tenant", "", "cursor", null);
        ReplayBatch blankCursor = replayStore.replay("tenant", "stream", "", null);

        assertThat(blankTenant.cursorFound()).isTrue();
        assertThat(blankTenant.events()).isEmpty();
        assertThat(blankStream.cursorFound()).isTrue();
        assertThat(blankStream.events()).isEmpty();
        assertThat(blankCursor.cursorFound()).isTrue();
        assertThat(blankCursor.events()).isEmpty();
    }

    private static ConsoleRealtimeStreamEnvelope envelope(
            String tenantId, String stream, String cursor, String eventType) {
        return new ConsoleRealtimeStreamEnvelope(
                "instance-1", tenantId, stream, eventType, cursor, false, "{}", Instant.now());
    }
}
