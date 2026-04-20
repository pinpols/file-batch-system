package com.example.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.config.WorkerDrainProperties;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkerHeartbeatTimeoutSchedulerTest {

  @Mock private WorkerRegistryMapper workerRegistryMapper;
  @Mock private OrchestratorGracefulShutdown gracefulShutdown;

  private final WorkerDrainProperties props = new WorkerDrainProperties();

  @InjectMocks private WorkerHeartbeatTimeoutScheduler scheduler;

  @BeforeEach
  void setUp() {
    props.setHeartbeatTimeoutSeconds(90);
  }

  @Test
  void computesCutoffFromNowAndTimeoutSeconds() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.markStaleHeartbeatsOffline(any())).thenReturn(0);
    scheduler = new WorkerHeartbeatTimeoutScheduler(workerRegistryMapper, props, gracefulShutdown);

    Instant before = Instant.now().minusSeconds(90);
    scheduler.markStaleWorkersOffline();
    Instant after = Instant.now().minusSeconds(90);

    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(workerRegistryMapper).markStaleHeartbeatsOffline(cutoffCaptor.capture());
    Instant cutoff = cutoffCaptor.getValue();
    assertThat(cutoff).isBetween(before.minusSeconds(1), after.plusSeconds(1));
  }

  @Test
  void drainingModeSkipsEntirely() {
    when(gracefulShutdown.isDraining()).thenReturn(true);
    scheduler = new WorkerHeartbeatTimeoutScheduler(workerRegistryMapper, props, gracefulShutdown);

    scheduler.markStaleWorkersOffline();

    verify(workerRegistryMapper, never()).markStaleHeartbeatsOffline(any());
  }

  @Test
  void customTimeoutSecondsIsRespected() {
    props.setHeartbeatTimeoutSeconds(300);
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.markStaleHeartbeatsOffline(any())).thenReturn(2);
    scheduler = new WorkerHeartbeatTimeoutScheduler(workerRegistryMapper, props, gracefulShutdown);

    Instant before = Instant.now().minusSeconds(300);
    scheduler.markStaleWorkersOffline();
    Instant after = Instant.now().minusSeconds(300);

    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(workerRegistryMapper).markStaleHeartbeatsOffline(cutoffCaptor.capture());
    assertThat(cutoffCaptor.getValue()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
  }
}
