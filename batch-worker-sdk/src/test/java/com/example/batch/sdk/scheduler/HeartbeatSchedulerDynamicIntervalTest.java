package com.example.batch.sdk.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.internal.PlatformHttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lane I:HeartbeatScheduler 消费 ADR-035 §11 的 {@code nextHeartbeatHint} 动态重排测试。
 *
 * <p>覆盖:hint=null / 极小 / 极大 / 正常 / cancel-after-stop / 多次相同 hint 不重排。
 */
class HeartbeatSchedulerDynamicIntervalTest {

  private static BatchPlatformClientConfig cfg(Duration heartbeat) {
    return BatchPlatformClientConfig.builder()
        .baseUrl("http://x")
        .tenantId("tx")
        .workerCode("w-1")
        .kafkaBootstrap("k:9092")
        .kafkaTopicPattern("t.*")
        .kafkaGroupId("g")
        .heartbeatInterval(heartbeat)
        .build();
  }

  private static Map<String, Object> respWithHint(Integer hintSeconds) {
    Map<String, Object> r = new HashMap<>();
    r.put("platformStatus", "NORMAL");
    if (hintSeconds != null) {
      r.put("nextHeartbeatHint", hintSeconds);
    }
    return r;
  }

  @Test
  @DisplayName("hint=null → 保持当前间隔,不触发 reschedule(向后兼容老回包)")
  void shouldKeepCurrentInterval_whenHintIsNull() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.heartbeat(any(), any())).thenReturn(respWithHint(null));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
    HeartbeatScheduler s =
        new HeartbeatScheduler(cfg(Duration.ofSeconds(10)), http, dispatcher, exec);
    s.start();

    s.tick();

    // 只有 start() 那一次 schedule,tick 不再重排
    verify(exec, times(1)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
    assertThat(s.currentIntervalMs()).isEqualTo(10_000L);
  }

  @Test
  @DisplayName("hint 正常值 → cancel 旧 future + 用新间隔重排")
  void shouldRescheduleWithNewInterval_whenHintIsNormal() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.heartbeat(any(), any())).thenReturn(respWithHint(20));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> firstFuture = mock(ScheduledFuture.class);
    ScheduledFuture<?> secondFuture = mock(ScheduledFuture.class);
    // start() 返回 firstFuture, tick reschedule 返回 secondFuture
    when(exec.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
        .thenAnswer(inv -> firstFuture)
        .thenAnswer(inv -> secondFuture);
    HeartbeatScheduler s =
        new HeartbeatScheduler(cfg(Duration.ofSeconds(10)), http, dispatcher, exec);
    s.start();

    s.tick();

    verify(firstFuture).cancel(false);
    verify(exec)
        .scheduleWithFixedDelay(
            any(Runnable.class), eq(20_000L), eq(20_000L), eq(TimeUnit.MILLISECONDS));
    assertThat(s.currentIntervalMs()).isEqualTo(20_000L);
  }

  @Test
  @DisplayName("hint < 1s → 兜底到 1s(防 orch 配错刷爆心跳)")
  void shouldClampToMinFloor_whenHintTooSmall() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    // hint=0 秒 → raw=0ms, clamp 到 1000ms
    when(http.heartbeat(any(), any())).thenReturn(respWithHint(0));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> firstFuture = mock(ScheduledFuture.class);
    when(exec.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
        .thenAnswer(inv -> firstFuture);
    HeartbeatScheduler s =
        new HeartbeatScheduler(cfg(Duration.ofSeconds(10)), http, dispatcher, exec);
    s.start();

    s.tick();

    verify(exec)
        .scheduleWithFixedDelay(
            any(Runnable.class), eq(1_000L), eq(1_000L), eq(TimeUnit.MILLISECONDS));
    assertThat(s.currentIntervalMs()).isEqualTo(1_000L);
  }

  @Test
  @DisplayName("hint > 10 × baseline → 兜底到 10 × baseline(防 orch 配错拖死心跳)")
  void shouldClampToMaxCeiling_whenHintTooLarge() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    // baseline=10s, 10× = 100s; hint=600s 应被 clamp 到 100s
    when(http.heartbeat(any(), any())).thenReturn(respWithHint(600));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> firstFuture = mock(ScheduledFuture.class);
    when(exec.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
        .thenAnswer(inv -> firstFuture);
    HeartbeatScheduler s =
        new HeartbeatScheduler(cfg(Duration.ofSeconds(10)), http, dispatcher, exec);
    s.start();

    s.tick();

    verify(exec)
        .scheduleWithFixedDelay(
            any(Runnable.class), eq(100_000L), eq(100_000L), eq(TimeUnit.MILLISECONDS));
    assertThat(s.currentIntervalMs()).isEqualTo(100_000L);
  }

  @Test
  @DisplayName("同一 hint 连续下发 → 不重复 cancel + 重排(幂等)")
  void shouldNotRescheduleAgain_whenSameHintRepeats() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.heartbeat(any(), any())).thenReturn(respWithHint(20));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> firstFuture = mock(ScheduledFuture.class);
    ScheduledFuture<?> secondFuture = mock(ScheduledFuture.class);
    when(exec.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
        .thenAnswer(inv -> firstFuture)
        .thenAnswer(inv -> secondFuture);
    HeartbeatScheduler s =
        new HeartbeatScheduler(cfg(Duration.ofSeconds(10)), http, dispatcher, exec);
    s.start();

    s.tick(); // 第一次 hint=20s,触发重排
    s.tick(); // 第二次相同,不应再 cancel/reschedule

    verify(firstFuture, times(1)).cancel(false);
    verify(exec, times(2)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
    verify(secondFuture, never()).cancel(false);
  }

  @Test
  @DisplayName("close() → cancel 当前 future 且 shutdown scheduler")
  void shouldCancelFutureAndShutdown_whenClosed() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> firstFuture = mock(ScheduledFuture.class);
    when(exec.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
        .thenAnswer(inv -> firstFuture);
    when(exec.awaitTermination(anyLong(), any())).thenReturn(true);
    HeartbeatScheduler s =
        new HeartbeatScheduler(cfg(Duration.ofSeconds(10)), http, dispatcher, exec);
    s.start();

    s.close();

    verify(firstFuture).cancel(false);
    verify(exec).shutdown();
  }

  @Test
  @DisplayName("heartbeat 抛异常 → swallow,不触发重排(directive=null 分支)")
  void shouldNotReschedule_whenHeartbeatThrows() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.heartbeat(any(), any())).thenThrow(new RuntimeException("boom"));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> firstFuture = mock(ScheduledFuture.class);
    when(exec.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
        .thenAnswer(inv -> firstFuture);
    HeartbeatScheduler s =
        new HeartbeatScheduler(cfg(Duration.ofSeconds(10)), http, dispatcher, exec);
    s.start();

    s.tick(); // 不应抛,且不重排

    verify(http, atLeastOnce()).heartbeat(any(), any());
    verify(firstFuture, never()).cancel(false);
    verify(exec, times(1)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
    assertThat(s.currentIntervalMs()).isEqualTo(10_000L);
  }
}
