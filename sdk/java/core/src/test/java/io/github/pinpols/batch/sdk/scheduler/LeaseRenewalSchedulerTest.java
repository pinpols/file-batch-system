package io.github.pinpols.batch.sdk.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.dispatcher.TaskDispatcher;
import io.github.pinpols.batch.sdk.internal.PlatformHttpClient;
import io.github.pinpols.batch.sdk.internal.PlatformHttpException;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("LeaseRenewalScheduler — 在途任务续租报文、取消信号翻转与错误分支")
class LeaseRenewalSchedulerTest {

  private final BatchPlatformClientConfig cfg = BatchPlatformClientConfig.builder()
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
  @DisplayName("每个在途任务各续租一次,请求体携带 worker 与租户")
  void shouldRenewEveryInFlightTask_whenTicked() throws Exception {
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
  @DisplayName("分区任务续租携带分区调用标识,避免平台拒绝导致重复执行")
  void shouldCarryPartitionInvocationId_whenRenewing() throws Exception {
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
  @DisplayName("无在途任务时不发起任何续租调用")
  void shouldSkipAllRenewals_whenNoInFlightTask() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of());
    try (LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher)) {
      s.tick();
    }

    verify(http, never()).renew(anyLong(), any());
  }

  @Test
  @DisplayName("单个任务续租异常不影响其余任务继续续租")
  void shouldContinueRemainingRenewals_whenOneRenewFails() throws Exception {
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
  @DisplayName("平台回包要求取消时翻转取消标记")
  void shouldSignalCancellation_whenPlatformRequestsCancel() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.renew(eq(10L), any())).thenReturn(new PlatformHttpClient.TaskRenewResponse(true));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of(10L));
    try (LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher)) {
      s.tick();
    }

    verify(dispatcher).markCancelled(10L, "platform-cancel");
  }

  @Test
  @DisplayName("平台未要求取消时不翻转取消标记")
  void shouldKeepRunning_whenPlatformKeepsLease() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.renew(eq(10L), any())).thenReturn(new PlatformHttpClient.TaskRenewResponse(false));
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.inFlightTaskIds()).thenReturn(Set.of(10L));
    try (LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg, http, dispatcher)) {
      s.tick();
    }

    verify(dispatcher, never()).markCancelled(anyLong(), any());
  }

  @Test
  @DisplayName("租约已被回收(410)时翻转取消标记,避免双跑")
  void shouldSignalCancellation_whenLeaseGoneWith410() throws Exception {
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
  @DisplayName("租约已被回收(404)与 410 走同一取消分支")
  void shouldSignalCancellation_whenLeaseGoneWith404() throws Exception {
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
  @DisplayName("存在进度快照时续租请求附带明细")
  void shouldIncludeProgressDetails_whenSnapshotPresent() throws Exception {
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
  @DisplayName("无进度快照时不带明细字段,其余字段照常")
  void shouldOmitDetails_whenSnapshotAbsent() throws Exception {
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
  @DisplayName("服务端 5xx 错误不翻转取消标记")
  void shouldNotSignalCancellation_whenServerErrorOccurs() throws Exception {
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
