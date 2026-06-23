package io.github.pinpols.batch.orchestrator.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
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
    Map<String, Object> status = shutdown.status();
    assertThat(status.get("reason")).isEqualTo("first");
  }

  @Test
  void shouldReportStatusCorrectly() {
    shutdown.startDraining("manual");

    Map<String, Object> status = shutdown.status();
    assertThat(status.get("draining")).isEqualTo(true);
    assertThat(status.get("drainingSince")).isNotNull();
    assertThat(status.get("reason")).isEqualTo("manual");
  }

  @Test
  void shouldDrainOnContextClosed() {
    ContextClosedEvent event = mock(ContextClosedEvent.class);

    shutdown.onApplicationEvent(event);

    assertThat(shutdown.isDraining()).isTrue();
    assertThat(shutdown.status().get("reason")).isEqualTo("context-closed");
  }
}
