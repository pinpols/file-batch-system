package com.example.batch.sdk.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.internal.PlatformHttpClient;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HeartbeatSchedulerTest {

  private final BatchPlatformClientConfig cfg =
      BatchPlatformClientConfig.builder()
          .baseUrl("http://x")
          .tenantId("tx")
          .workerCode("w-1")
          .kafkaBootstrap("k:9092")
          .kafkaTopicPattern("t.*")
          .kafkaGroupId("g")
          .maxConcurrentTasks(4)
          .build();

  @Test
  void tickPostsHeartbeatWithDispatcherStats() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightCount()).thenReturn(2);
    HeartbeatScheduler s = new HeartbeatScheduler(cfg, http, dispatcher);

    s.tick();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
    verify(http).heartbeat(eq("w-1"), body.capture());
    assertThat(body.getValue())
        .containsEntry("tenantId", "tx")
        .containsEntry("workerCode", "w-1")
        .containsEntry("currentLoad", 2)
        .containsEntry("status", "RUNNING")
        .containsKey("heartbeatAt");
  }

  @Test
  void heartbeatFailureSwallowedDoesNotKillScheduler() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.heartbeat(anyString(), any())).thenThrow(new IOException("503"));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    HeartbeatScheduler s = new HeartbeatScheduler(cfg, http, dispatcher);

    s.tick(); // 不应抛
    s.tick();

    verify(http, atLeastOnce()).heartbeat(anyString(), any());
  }

  @Test
  void closeIsIdempotent() {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    HeartbeatScheduler s = new HeartbeatScheduler(cfg, http, dispatcher);
    s.close();
    s.close(); // 不应抛
  }

  @Test
  void startUsesFixedDelayNotFixedRate() {
    // #SDK-P1-3:平台短暂卡顿后,SDK 不应追赶式连发心跳,必须用 scheduleWithFixedDelay
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
    BatchPlatformClientConfig fastCfg =
        cfg.toBuilder().heartbeatInterval(Duration.ofMillis(123)).build();
    HeartbeatScheduler s = new HeartbeatScheduler(fastCfg, http, dispatcher, exec);

    s.start();

    verify(exec)
        .scheduleWithFixedDelay(any(Runnable.class), eq(123L), eq(123L), eq(TimeUnit.MILLISECONDS));
    verify(exec, never()).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any());
  }
}
