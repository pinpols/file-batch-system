package com.example.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.application.WorkerRuntimeFacade;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.domain.WorkerRegistration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for AbstractWorkerLoop:
 * - ensureStarted() is idempotent (registers only once)
 * - registration is correctly populated from WorkerConfiguration
 * - doHeartbeat() delegates to WorkerRuntimeFacade.heartbeat()
 * - doHeartbeat() is safe when not yet started
 * - shutdown() delegates to WorkerRuntimeFacade.shutdown()
 * - shutdown() is safe when never started
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AbstractWorkerLoopTest {

    @Mock
    private WorkerRuntimeFacade workerRuntimeFacade;

    private TestWorkerLoop loop;

    @BeforeEach
    void setUp() {
        WorkerRegistration registration = new WorkerRegistration();
        registration.setWorkerId("test-worker-001");
        registration.setTenantId("t1");
        when(workerRuntimeFacade.start(any())).thenReturn(registration);

        loop = new TestWorkerLoop(workerRuntimeFacade);
    }

    @Test
    void ensureStarted_registersWorkerOnFirstCall() {
        WorkerRegistration result = loop.ensureStarted();

        assertThat(result).isNotNull();
        assertThat(result.getWorkerId()).isEqualTo("test-worker-001");
        verify(workerRuntimeFacade, times(1)).start(any());
    }

    @Test
    void ensureStarted_isIdempotent_registersOnlyOnce() {
        loop.ensureStarted();
        loop.ensureStarted();
        loop.ensureStarted();

        verify(workerRuntimeFacade, times(1)).start(any());
    }

    @Test
    void ensureStarted_populatesRegistrationFromConfiguration() {
        loop.ensureStarted();

        ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
        verify(workerRuntimeFacade).start(captor.capture());

        WorkerRegistration sent = captor.getValue();
        assertThat(sent.getTenantId()).isEqualTo("t1");
        assertThat(sent.getWorkerType()).isEqualTo("TEST");
        assertThat(sent.getWorkerGroup()).isEqualTo("test");
        assertThat(sent.getPort()).isEqualTo(9999);
        assertThat(sent.getActive()).isTrue();
        assertThat(sent.getRegisteredAt()).isNotNull();
        assertThat(sent.getLastHeartbeatAt()).isNotNull();
    }

    @Test
    void ensureStarted_buildWorkerIdFromWorkerCode_whenPresent() {
        loop.ensureStarted();

        ArgumentCaptor<WorkerRegistration> captor = ArgumentCaptor.forClass(WorkerRegistration.class);
        verify(workerRuntimeFacade).start(captor.capture());
        assertThat(captor.getValue().getWorkerId()).isEqualTo("fixed-worker-code");
    }

    @Test
    void doHeartbeat_sendsHeartbeatAfterStart() {
        loop.ensureStarted();
        loop.doHeartbeat();

        verify(workerRuntimeFacade, times(1)).heartbeat("test-worker-001");
    }

    @Test
    void doHeartbeat_doesNotFailBeforeStart() {
        // Create a loop where start() is not called first
        TestWorkerLoop freshLoop = new TestWorkerLoop(workerRuntimeFacade);
        // doHeartbeat on a loop that hasn't started should still trigger ensureStarted internally
        // The behaviour: ensureStarted() returns a valid registration, heartbeat then proceeds
        freshLoop.doHeartbeat();

        verify(workerRuntimeFacade, times(1)).start(any());
        verify(workerRuntimeFacade, times(1)).heartbeat("test-worker-001");
    }

    @Test
    void doHeartbeat_continuesGracefullyWhenFacadeThrows() {
        loop.ensureStarted();
        org.mockito.Mockito.doThrow(new RuntimeException("network error"))
                .when(workerRuntimeFacade).heartbeat(any());

        // must not propagate
        loop.doHeartbeat();
    }

    @Test
    void shutdown_delegatesToFacade_whenStarted() {
        loop.ensureStarted();
        loop.shutdown();

        verify(workerRuntimeFacade, times(1)).shutdown("test-worker-001");
    }

    @Test
    void shutdown_isNoOp_whenNeverStarted() {
        TestWorkerLoop freshLoop = new TestWorkerLoop(workerRuntimeFacade);
        freshLoop.shutdown();

        verify(workerRuntimeFacade, never()).shutdown(any());
    }

    @Test
    void shutdown_doesNotPropagateFacadeFailure() {
        loop.ensureStarted();
        org.mockito.Mockito.doThrow(new RuntimeException("shutdown failed"))
                .when(workerRuntimeFacade).shutdown(any());

        loop.shutdown();

        verify(workerRuntimeFacade, times(1)).shutdown("test-worker-001");
    }

    // ── minimal concrete subclass for testing ──────────────────────────────

    private static class TestWorkerLoop extends AbstractWorkerLoop {

        TestWorkerLoop(WorkerRuntimeFacade facade) {
            super(facade);
        }

        @Override
        protected WorkerConfiguration workerConfiguration() {
            return new WorkerConfiguration() {
                public String workerCode()              { return "fixed-worker-code"; }
                public String workerType()              { return "TEST"; }
                public String tenantId()                { return "t1"; }
                public Long heartbeatIntervalMillis()   { return 15000L; }
                public String topic()                   { return "test-topic"; }
                public String consumerGroupId()         { return "test-group"; }
            };
        }

        @Override protected String workerGroup() { return "test"; }
        @Override protected int workerPort()     { return 9999; }
    }
}
