package io.github.pinpols.batch.console.domain.notification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.service.JobPushDeliveryService;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("持久推送队列轮询的启动与关闭")
class ConsolePushJobNotifierTest {
  private final ConsolePushProperties properties = new ConsolePushProperties();
  private final JobPushDeliveryService deliveries = mock(JobPushDeliveryService.class);
  private final ConsolePushJobNotifier notifier =
      new ConsolePushJobNotifier(properties, deliveries);

  @DisplayName("仅轮询持久设备队列并传递实时关闭状态")
  @Test
  void shouldPollDurableQueueWithShutdownState_whenTickRuns() {
    notifier.pollOnce();
    var stopping = ArgumentCaptor.forClass(BooleanSupplier.class);
    verify(deliveries).poll(stopping.capture());
    assertThat(stopping.getValue().getAsBoolean()).isFalse();
    notifier.stop();
    assertThat(stopping.getValue().getAsBoolean()).isTrue();
  }

  @DisplayName("停止后禁止新轮询")
  @Test
  void shouldPreventNewPolls_whenStopped() {
    notifier.stop();
    notifier.pollOnce();
    notifier.pollSafely();
    verifyNoInteractions(deliveries);
  }

  @DisplayName("上下文关闭后不再轮询")
  @Test
  void shouldStopPolling_whenContextClosed() {
    notifier.stopOnContextClosed(null);
    notifier.pollOnce();
    verifyNoInteractions(deliveries);
  }

  @DisplayName("临时轮询失败不终止后续调度回调")
  @Test
  void shouldKeepSchedulerAlive_whenPollFailsTransiently() {
    doThrow(new IllegalStateException("database unavailable"))
        .doNothing()
        .when(deliveries)
        .poll(any());
    assertThatCode(notifier::pollSafely).doesNotThrowAnyException();
    assertThatCode(notifier::pollSafely).doesNotThrowAnyException();
    verify(deliveries, times(2)).poll(any());
  }

  @DisplayName("已启用调度器可在首次回调前停止")
  @Test
  void shouldStopBeforeFirstTick_whenSchedulerEnabled() {
    properties.setEnabled(true);
    properties.getJobNotify().setEnabled(true);
    properties.getJobNotify().setPollIntervalMillis(600000);
    try {
      notifier.start();
    } finally {
      notifier.stop();
    }
    notifier.pollOnce();
    verifyNoInteractions(deliveries);
  }
}
