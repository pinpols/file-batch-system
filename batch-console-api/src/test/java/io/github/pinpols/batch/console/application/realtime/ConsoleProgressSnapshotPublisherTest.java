package io.github.pinpols.batch.console.application.realtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
@DisplayName("进度完整快照的共享采样与连接资源回收")
class ConsoleProgressSnapshotPublisherTest {
  @Mock
  private ConsoleRealtimeSubscriptionPort subscriptions;

  @Mock
  private ConsoleRealtimeEventPort events;

  @Mock
  private TaskScheduler scheduler;

  @Mock
  private ScheduledFuture<?> future;

  @Mock
  private SseEmitter emitter;

  private ConsoleProgressSnapshotPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher = new ConsoleProgressSnapshotPublisher(subscriptions, events, scheduler);
  }

  @Test
  @DisplayName("同一租户和日期共享采样且只有最后连接结束才停止")
  void shouldShareAndReleaseSampling_whenSubscriptionsClose() {
    doReturn(future)
        .when(scheduler)
        .scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
    when(subscriptions.subscribe("ta", "daily-2040", null, null, null)).thenReturn(emitter);
    AtomicInteger count = new AtomicInteger();
    publisher.subscribe("ta", "daily-2040", "daily-progress", 0, count::get, value -> value);
    publisher.subscribe("ta", "daily-2040", "daily-progress", 0, count::get, value -> value);
    var sample = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).scheduleWithFixedDelay(sample.capture(), any(Duration.class));
    sample.getValue().run();
    verify(events, times(2)).publishChanged("ta", "daily-2040", "daily-progress", 0);
    count.incrementAndGet();
    sample.getValue().run();
    verify(events).publishChanged("ta", "daily-2040", "daily-progress", 1);
    var close = ArgumentCaptor.forClass(Runnable.class);
    verify(emitter, times(2)).onCompletion(close.capture());
    close.getAllValues().get(0).run();
    close.getAllValues().get(0).run();
    verify(future, never()).cancel(false);
    close.getAllValues().get(1).run();
    verify(future).cancel(false);
  }

  @Test
  @DisplayName("订阅失败取消后台任务，不泄漏采样槽")
  void shouldReleaseSampler_whenStreamFailsToOpen() {
    doReturn(future)
        .when(scheduler)
        .scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
    when(subscriptions.subscribe("ta", "daily-2040", null, null, null))
        .thenThrow(new IllegalStateException("closed"));
    assertThatThrownBy(() ->
            publisher.subscribe("ta", "daily-2040", "daily-progress", 0, () -> 1, value -> value))
        .isInstanceOf(IllegalStateException.class);
    verify(future).cancel(false);
  }

  @Test
  @DisplayName("失败推送不提前推进水位，恢复后仍发布完整状态且不泄露原始异常")
  void shouldRetryChangedSnapshot_whenPublisherFails() {
    doReturn(future)
        .when(scheduler)
        .scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
    when(subscriptions.subscribe("ta", "daily-2040", null, null, null)).thenReturn(emitter);
    publisher.subscribe("ta", "daily-2040", "daily-progress", 0, () -> 1, value -> value);
    var sample = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).scheduleWithFixedDelay(sample.capture(), any(Duration.class));
    doThrow(new IllegalStateException("sensitive"))
        .doNothing()
        .when(events)
        .publishChanged("ta", "daily-2040", "daily-progress", 1);
    sample.getValue().run();
    sample.getValue().run();
    verify(events, times(2)).publishChanged("ta", "daily-2040", "daily-progress", 1);
    verify(events)
        .publishChanged(
            "ta", "daily-2040", "daily-progress-error", Map.of("reason", "SNAPSHOT_UNAVAILABLE"));
    publisher.stop();
  }
}
