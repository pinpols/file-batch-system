package com.example.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.context.event.ContextClosedEvent;

class TriggerGracefulShutdownTest {

  private Scheduler scheduler;
  private TriggerGracefulShutdown shutdown;

  @BeforeEach
  void setUp() {
    scheduler = mock(Scheduler.class);
    shutdown = new TriggerGracefulShutdown(scheduler);
  }

  @Test
  void shouldNotBeDrainingInitially() {
    assertThat(shutdown.isDraining()).isFalse();
  }

  @Test
  void shouldStartDraining() throws SchedulerException {
    shutdown.startDraining("test");

    assertThat(shutdown.isDraining()).isTrue();
    verify(scheduler).standby();
  }

  @Test
  void shouldStopDraining() throws SchedulerException {
    when(scheduler.isShutdown()).thenReturn(false);

    shutdown.startDraining("test");
    shutdown.stopDraining("cancel");

    assertThat(shutdown.isDraining()).isFalse();
    verify(scheduler).start();
  }

  @Test
  void shouldReportSchedulerStatus() throws SchedulerException {
    when(scheduler.isShutdown()).thenReturn(false);
    when(scheduler.isInStandbyMode()).thenReturn(true);
    when(scheduler.isStarted()).thenReturn(false);

    Map<String, Object> status = shutdown.status();

    assertThat(status.get("draining")).isEqualTo(false);
    assertThat(status.get("schedulerStatus")).isEqualTo("STANDBY");
  }

  @Test
  void shouldShutdownOnContextClosed() throws SchedulerException {
    when(scheduler.isShutdown()).thenReturn(false);
    ContextClosedEvent event = mock(ContextClosedEvent.class);

    shutdown.onApplicationEvent(event);

    assertThat(shutdown.isDraining()).isTrue();
    verify(scheduler).shutdown(true);
  }
}
