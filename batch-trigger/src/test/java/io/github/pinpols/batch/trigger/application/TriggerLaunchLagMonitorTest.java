package io.github.pinpols.batch.trigger.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class TriggerLaunchLagMonitorTest {

  @Test
  void stoppedMonitorDoesNotCreateKafkaAdminClient() {
    KafkaAdmin kafkaAdmin = mock(KafkaAdmin.class);
    TriggerLaunchLagMonitor monitor = new TriggerLaunchLagMonitor(
        kafkaAdmin,
        new TriggerOutboxRelayProperties(),
        new SimpleMeterRegistry(),
        mock(ThreadPoolTaskScheduler.class));

    monitor.stop();
    monitor.sampleSafely();

    verifyNoInteractions(kafkaAdmin);
  }
}
