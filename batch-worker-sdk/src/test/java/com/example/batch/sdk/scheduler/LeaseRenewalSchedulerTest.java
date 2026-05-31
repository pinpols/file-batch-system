package com.example.batch.sdk.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.internal.PlatformHttpClient;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LeaseRenewalSchedulerTest {

  private final BatchPlatformClientConfig cfg =
      BatchPlatformClientConfig.builder()
          .baseUrl("http://x")
          .tenantId("tx")
          .workerCode("w-1")
          .kafkaBootstrap("k:9092")
          .kafkaTopicPattern("t.*")
          .kafkaGroupId("g")
          .build();

  @Test
  void tickRenewsEveryInFlightTask() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of(10L, 20L, 30L));
    LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher);

    s.tick();

    verify(http).renewLease(eq(10L), any());
    verify(http).renewLease(eq(20L), any());
    verify(http).renewLease(eq(30L), any());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
    verify(http, times(3)).renewLease(anyLong(), body.capture());
    assertThat(body.getValue()).containsEntry("workerCode", "w-1");
  }

  @Test
  void emptyInFlightSkipsAllCalls() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of());
    LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher);

    s.tick();

    verify(http, never()).renewLease(anyLong(), any());
  }

  @Test
  void singleTaskFailureDoesNotStopOthers() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.renewLease(eq(10L), any())).thenThrow(new IOException("404 expired"));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of(10L, 20L));
    LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher);

    s.tick(); // 不应抛

    verify(http).renewLease(eq(20L), any()); // 20 还是被尝试
  }

  private static <T> T eq(T v) {
    return org.mockito.ArgumentMatchers.eq(v);
  }
}
