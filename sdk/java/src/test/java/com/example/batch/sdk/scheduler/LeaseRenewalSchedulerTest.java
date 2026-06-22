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
import com.example.batch.sdk.internal.PlatformHttpException;
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

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
    return ArgumentCaptor.forClass((Class) Map.class);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMap(Object value) {
    return (Map<String, Object>) value;
  }

  @Test
  void tickRenewsEveryInFlightTask() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of(10L, 20L, 30L));
    try (LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher)) {
      s.tick();
    }

    verify(http).renew(eq(10L), any());
    verify(http).renew(eq(20L), any());
    verify(http).renew(eq(30L), any());

    ArgumentCaptor<Map<String, Object>> body = mapCaptor();
    verify(http, times(3)).renew(anyLong(), body.capture());
    assertThat(body.getValue()).containsEntry("workerId", "w-1").containsEntry("tenantId", "tx");
  }

  @Test
  void renewCarriesPartitionInvocationId() throws Exception {
    // C1 回归守护:分区任务 renew 必须带 partitionInvocationId,否则平台 R3-P1-10 返 409 → 不续租 → 双跑。
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of(42L));
    when(dispatcher.partitionInvocation(42L)).thenReturn("inv-77");
    try (LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher)) {
      s.tick();
    }

    ArgumentCaptor<Map<String, Object>> body = mapCaptor();
    verify(http).renew(eq(42L), body.capture());
    assertThat(body.getValue()).containsEntry("partitionInvocationId", "inv-77");
  }

  @Test
  void emptyInFlightSkipsAllCalls() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of());
    try (LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher)) {
      s.tick();
    }

    verify(http, never()).renew(anyLong(), any());
  }

  @Test
  void singleTaskFailureDoesNotStopOthers() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.renew(eq(10L), any())).thenThrow(new IOException("404 expired"));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of(10L, 20L));
    try (LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher)) {
      s.tick(); // 不应抛
    }

    verify(http).renew(eq(20L), any()); // 20 还是被尝试
  }

  @Test
  void cancelRequestedResponseSignalsCancellation() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.renew(eq(10L), any())).thenReturn(Map.of("cancelRequested", true));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of(10L));
    try (LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher)) {
      s.tick();
    }

    verify(dispatcher).markCancelled(10L, "platform-cancel");
  }

  @Test
  void cancelNotRequestedDoesNotSignal() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.renew(eq(10L), any())).thenReturn(Map.of("cancelRequested", false));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of(10L));
    try (LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher)) {
      s.tick();
    }

    verify(dispatcher, never()).markCancelled(anyLong(), any());
  }

  @Test
  void revokedLeaseSignalsCancellation() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.renew(eq(10L), any())).thenThrow(new PlatformHttpException(410, "gone"));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of(10L));
    try (LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher)) {
      s.tick();
    }

    verify(dispatcher).markCancelled(10L, "lease-revoked");
  }

  @Test
  void revoked404LeaseSignalsCancellationSameAs410() throws Exception {
    // 固化:renewOne 对 404 与 410 走同一分支(lease 被回收),都翻转取消信号避免双跑。
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.renew(eq(10L), any())).thenThrow(new PlatformHttpException(404, "not found"));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of(10L));
    try (LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher)) {
      s.tick();
    }

    verify(dispatcher).markCancelled(10L, "lease-revoked");
  }

  @Test
  void progressSnapshotIncludedAsDetails() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of(10L));
    when(dispatcher.progressSnapshot(10L)).thenReturn(Map.of("processed", 5, "total", 100));
    try (LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher)) {
      s.tick();
    }

    ArgumentCaptor<Map<String, Object>> body = mapCaptor();
    verify(http).renew(eq(10L), body.capture());
    Map<String, Object> details = objectMap(body.getValue().get("details"));
    assertThat(details).containsEntry("processed", 5).containsEntry("total", 100);
  }

  @Test
  void noProgressOmitsDetails() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of(10L));
    when(dispatcher.progressSnapshot(10L)).thenReturn(null);
    try (LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher)) {
      s.tick();
    }

    ArgumentCaptor<Map<String, Object>> body = mapCaptor();
    verify(http).renew(eq(10L), body.capture());
    assertThat(body.getValue()).doesNotContainKey("details").containsEntry("workerId", "w-1");
  }

  @Test
  void otherHttpErrorDoesNotSignal() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.renew(eq(10L), any())).thenThrow(new PlatformHttpException(500, "boom"));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of(10L));
    try (LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher)) {
      s.tick();
    }

    verify(dispatcher, never()).markCancelled(anyLong(), any());
  }

  private static <T> T eq(T v) {
    return org.mockito.ArgumentMatchers.eq(v);
  }
}
