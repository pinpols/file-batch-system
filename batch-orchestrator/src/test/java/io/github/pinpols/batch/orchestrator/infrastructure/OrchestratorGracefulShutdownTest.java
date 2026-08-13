package io.github.pinpols.batch.orchestrator.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;

class OrchestratorGracefulShutdownTest {

  private OrchestratorGracefulShutdown shutdown;

  @BeforeEach
  void setUp() {
    shutdown = new OrchestratorGracefulShutdown();
  }

  @Test
  void shouldNotBeDrainingInitially() {
    assertThat(shutdown.isDraining()).isFalse();
  }

  @Test
  void shouldStartDraining() {
    shutdown.startDraining("test");

    assertThat(shutdown.isDraining()).isTrue();
  }

  @Test
  void shouldStopDraining() {
    shutdown.startDraining("test");
    shutdown.stopDraining("cancel");

    assertThat(shutdown.isDraining()).isFalse();
  }

  @Test
  void shouldNotStartDrainingTwice() {
    shutdown.startDraining("first");
    shutdown.startDraining("second");

    assertThat(shutdown.isDraining()).isTrue();
    OrchestratorGracefulShutdown.DrainStatus status = shutdown.status();
    assertThat(status.reason()).isEqualTo("first");
  }

  @Test
  void shouldReportStatusCorrectly() {
    shutdown.startDraining("manual");

    OrchestratorGracefulShutdown.DrainStatus status = shutdown.status();
    assertThat(status.draining()).isTrue();
    assertThat(status.drainingSince()).isNotNull();
    assertThat(status.reason()).isEqualTo("manual");
  }

  @Test
  void shouldDrainOnContextClosed() {
    ContextClosedEvent event = mock(ContextClosedEvent.class);

    shutdown.onApplicationEvent(event);

    assertThat(shutdown.isDraining()).isTrue();
    assertThat(shutdown.status().reason()).isEqualTo("context-closed");
  }
}
