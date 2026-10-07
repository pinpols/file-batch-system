package io.github.pinpols.batch.trigger.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@DisplayName("Trigger 启动延迟监控:采样受启停开关控制,lag 查询端口只在运行期被访问")
class TriggerLaunchLagMonitorTest {

  @Test
  @DisplayName("监控 stop() 后 sampleSafely() 不得触碰 lag 查询端口,避免停机后仍打库")
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
