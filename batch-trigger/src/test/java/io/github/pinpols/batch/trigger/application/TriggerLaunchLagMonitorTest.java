package io.github.pinpols.batch.trigger.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class TriggerLaunchLagMonitorTest {

  @Test
  void stoppedMonitorDoesNotSampleLag() {
    TriggerLaunchLagQueryPort lagQuery = mock(TriggerLaunchLagQueryPort.class);
    TriggerLaunchLagMonitor monitor = new TriggerLaunchLagMonitor(
        lagQuery,
        new TriggerOutboxRelayProperties(),
        new SimpleMeterRegistry(),
        mock(ThreadPoolTaskScheduler.class));

    monitor.stop();
    monitor.sampleSafely();

    verifyNoInteractions(lagQuery);
  }
}
