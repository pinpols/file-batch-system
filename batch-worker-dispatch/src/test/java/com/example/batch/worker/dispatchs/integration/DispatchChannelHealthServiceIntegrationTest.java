package com.example.batch.worker.dispatchs.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.dispatchs.BatchWorkerDispatchApplication;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchChannelHealthRepository;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchChannelHealthService;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchChannelHealthSnapshot;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration test: DispatchChannelHealthService records outcomes to the real business DB
 * and retrieves consistent health snapshots.
 */
@SpringBootTest(
        classes = BatchWorkerDispatchApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DispatchChannelHealthServiceIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void orchestratorStub(DynamicPropertyRegistry registry) {
        OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
    }

    @Autowired
    private DispatchChannelHealthService healthService;

    @Autowired
    private DispatchChannelHealthRepository healthRepository;

    @Test
    void shouldPersistHealthySnapshotOnSuccessfulDispatch() {
        Map<String, Object> channelConfig = channelConfig("t1", "ch-001", "API");

        healthService.recordDispatchOutcome(channelConfig, true, "ok", null);

        DispatchChannelHealthSnapshot snapshot = healthRepository.findHealth("t1", "ch-001");
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.healthStatus()).isEqualTo("HEALTHY");
        assertThat(snapshot.consecutiveFailures()).isEqualTo(0);
        assertThat(snapshot.lastSuccessAt()).isNotNull();
    }

    @Test
    void shouldIncrementConsecutiveFailuresOnFailure() {
        Map<String, Object> channelConfig = channelConfig("t1", "ch-002", "SFTP");

        healthService.recordDispatchOutcome(channelConfig, false, "timeout", null);
        healthService.recordDispatchOutcome(channelConfig, false, "timeout", null);

        DispatchChannelHealthSnapshot snapshot = healthRepository.findHealth("t1", "ch-002");
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.consecutiveFailures()).isEqualTo(2);
        assertThat(snapshot.lastFailureAt()).isNotNull();
    }

    @Test
    void shouldResetConsecutiveFailuresAfterSuccess() {
        Map<String, Object> channelConfig = channelConfig("t1", "ch-003", "NAS");

        healthService.recordDispatchOutcome(channelConfig, false, "error", null);
        healthService.recordDispatchOutcome(channelConfig, false, "error", null);
        healthService.recordDispatchOutcome(channelConfig, true, "ok", null);

        DispatchChannelHealthSnapshot snapshot = healthRepository.findHealth("t1", "ch-003");
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.healthStatus()).isEqualTo("HEALTHY");
        assertThat(snapshot.consecutiveFailures()).isEqualTo(0);
    }

    @Test
    void shouldAllowDispatchWhenNoHealthSnapshotExists() {
        Map<String, Object> channelConfig = channelConfig("t1", "ch-new-999", "API");
        // no prior health record — should default to allow
        assertThat(healthService.allowDispatch(channelConfig)).isTrue();
    }

    @Test
    void shouldAllowDispatchForHealthyChannel() {
        Map<String, Object> channelConfig = channelConfig("t1", "ch-004", "API");
        healthService.recordDispatchOutcome(channelConfig, true, "ok", null);

        assertThat(healthService.allowDispatch(channelConfig)).isTrue();
    }

    @Test
    void shouldBlockDispatchForUnhealthyChannelBeforeBackoffExpires() {
        Map<String, Object> channelConfig = channelConfig("t1", "ch-005", "API");
        // trigger enough failures to set UNHEALTHY (default threshold is 5)
        for (int i = 0; i < 5; i++) {
            healthService.recordDispatchOutcome(channelConfig, false, "error", null);
        }

        DispatchChannelHealthSnapshot snapshot = healthRepository.findHealth("t1", "ch-005");
        assertThat(snapshot.healthStatus()).isIn("UNHEALTHY", "DEGRADED");
        // backoff has not expired, so dispatch should be blocked
        assertThat(healthService.allowDispatch(channelConfig)).isFalse();
    }

    // --- helpers ---

    private static Map<String, Object> channelConfig(String tenantId, String channelCode, String channelType) {
        return Map.of(
                "tenant_id", tenantId,
                "channel_code", channelCode,
                "channel_type", channelType
        );
    }
}
