package io.github.pinpols.batch.console.domain.notification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.DeliveryOutcome;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.PushPayload;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import nl.martijndwars.webpush.Notification;
import org.asynchttpclient.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

@DisplayName("推送发送器: 开关判定, 初始化失败兜底与投递目标查找的生命周期")
class ConsolePushSenderTest {

  @DisplayName("保留中断并停止向剩余设备发送")
  @Test
  void shouldPreserveInterruptAndAvoidSendingToRemainingDevices() throws Exception {
    ManagedWebPushClient service = mock(ManagedWebPushClient.class);
    @SuppressWarnings("unchecked")
    CompletableFuture<Response> pending = mock(CompletableFuture.class);
    when(pending.get(3L, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
    when(service.send(any(Notification.class))).thenReturn(pending);
    pushServiceRef(sender).set(service);
    ConsolePushSubscriptionEntity first = subscription();
    ConsolePushSubscriptionEntity second = subscription();
    second.setId(43L);
    second.setEndpoint("https://fcm.googleapis.com/fcm/send/second-test-device");
    when(repository.findByTenantAndUser("tenant-a", "alice")).thenReturn(List.of(first, second));
    try {
      sender.sendToUser("tenant-a", "alice", new PushPayload("title", null, null, null));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      verify(service, times(1)).send(any(Notification.class));
      verify(repository, never()).touchIfUnchanged(any(), any());
      verify(repository, never()).deleteIfUnchanged(any());
    } finally {
      Thread.interrupted();
    }
  }

  @DisplayName("返回供应商结果且不宣称浏览器已送达")
  @ParameterizedTest
  @CsvSource({
    "201,ACCEPTED",
    "204,ACCEPTED",
    "404,GONE",
    "410,GONE",
    "429,RETRYABLE_FAILURE",
    "500,RETRYABLE_FAILURE",
    "503,RETRYABLE_FAILURE",
    "400,PERMANENT_FAILURE",
    "401,PERMANENT_FAILURE",
    "403,PERMANENT_FAILURE",
    "413,PERMANENT_FAILURE",
    "302,PERMANENT_FAILURE"
  })
  void shouldReturnProviderOutcomeWithoutClaimingBrowserDelivery(int code, DeliveryOutcome outcome)
      throws Exception {
    ManagedWebPushClient service = configuredService(code);
    var result = sender.sendToSubscription(
        subscription(), new PushPayload("title", "body", "tag", "/m/jobs"));
    assertThat(result.outcome()).isEqualTo(outcome);
    assertThat(result.httpStatus()).isEqualTo(code);
    verify(service).send(any(Notification.class));
    if (outcome == DeliveryOutcome.ACCEPTED) {
      verify(repository).touchIfUnchanged(any(), any());
    } else {
      verify(repository, never()).touchIfUnchanged(any(), any());
    }
  }

  @DisplayName("本地记账失败仍保留供应商已接纳事实")
  @Test
  void shouldRetainAcceptanceWhenLocalBookkeepingFails() throws Exception {
    configuredService(201);
    doThrow(new IllegalStateException("database unavailable"))
        .when(repository)
        .touchIfUnchanged(any(), any());
    var result =
        sender.sendToSubscription(subscription(), new PushPayload("title", null, null, null));
    assertThat(result.outcome()).isEqualTo(DeliveryOutcome.ACCEPTED);
    assertThat(result.httpStatus()).isEqualTo(201);
  }

  @DisplayName("本地清理失败仍保留端点已失效结果")
  @Test
  void shouldRetainGoneResponseWhenLocalCleanupFails() throws Exception {
    configuredService(410);
    doThrow(new IllegalStateException("database unavailable"))
        .when(repository)
        .deleteIfUnchanged(any());
    var result =
        sender.sendToSubscription(subscription(), new PushPayload("title", null, null, null));
    assertThat(result.outcome()).isEqualTo(DeliveryOutcome.GONE);
    assertThat(result.httpStatus()).isEqualTo(410);
  }

  @DisplayName("发送器未就绪时不查询设备")
  @Test
  void shouldReturnNotReadyWithoutLookingUpDevices() throws Exception {
    var result =
        sender.sendToSubscription(subscription(), new PushPayload("title", null, null, null));
    assertThat(result.outcome()).isEqualTo(DeliveryOutcome.NOT_READY);
    assertThat(result.httpStatus()).isNull();
    verifyNoInteractions(repository);
  }

  @DisplayName("响应超时记为结果未知")
  @Test
  void shouldTreatResponseTimeoutAsUnknownRatherThanSuccessOrSafeRejection() throws Exception {
    ManagedWebPushClient service = mock(ManagedWebPushClient.class);
    @SuppressWarnings("unchecked")
    CompletableFuture<Response> pending = mock(CompletableFuture.class);
    when(pending.get(3L, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
    when(service.send(any(Notification.class))).thenReturn(pending);
    pushServiceRef(sender).set(service);
    var result =
        sender.sendToSubscription(subscription(), new PushPayload("title", null, null, null));
    assertThat(result.outcome()).isEqualTo(DeliveryOutcome.UNKNOWN);
    assertThat(result.httpStatus()).isNull();
    verify(pending).get(3L, TimeUnit.SECONDS);
    verifyNoInteractions(repository);
  }

  @DisplayName("传输失败记为结果未知")
  @Test
  void shouldTreatTransportFailureAsUnknown() throws Exception {
    ManagedWebPushClient service = mock(ManagedWebPushClient.class);
    when(service.send(any(Notification.class)))
        .thenReturn(CompletableFuture.failedFuture(new IOException("connection lost")));
    pushServiceRef(sender).set(service);
    var result =
        sender.sendToSubscription(subscription(), new PushPayload("title", null, null, null));
    assertThat(result.outcome()).isEqualTo(DeliveryOutcome.UNKNOWN);
    verifyNoInteractions(repository);
  }

  @DisplayName("提交前拒绝非法设备密钥")
  @Test
  void shouldRejectInvalidDeviceKeysBeforeSubmitting() throws Exception {
    ManagedWebPushClient service = configuredService(201);
    ConsolePushSubscriptionEntity sub = subscription();
    sub.setP256dhKey("invalid key");
    var result = sender.sendToSubscription(sub, new PushPayload("title", null, null, null));
    assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PERMANENT_FAILURE);
    verify(service, never()).send(any(Notification.class));
    verifyNoInteractions(repository);
  }

  private ManagedWebPushClient configuredService(int status) throws Exception {
    ManagedWebPushClient service = mock(ManagedWebPushClient.class);
    Response response = mock(Response.class);
    when(response.getStatusCode()).thenReturn(status);
    when(service.send(any(Notification.class)))
        .thenReturn(CompletableFuture.completedFuture(response));
    pushServiceRef(sender).set(service);
    return service;
  }

  @DisplayName("失效端点仅在所属租户和用户内清理")
  @ParameterizedTest
  @ValueSource(ints = {404, 410})
  void shouldOnlyRemoveGoneEndpointForItsTenantAndUser(int status) throws Exception {
    ManagedWebPushClient service = mock(ManagedWebPushClient.class);
    Response response = mock(Response.class);
    when(response.getStatusCode()).thenReturn(status);
    when(service.send(any(Notification.class)))
        .thenReturn(CompletableFuture.completedFuture(response));
    pushServiceRef(sender).set(service);
    ConsolePushSubscriptionEntity sub = subscription();
    when(repository.findByTenantAndUser("tenant-a", "alice")).thenReturn(List.of(sub));

    sender.sendToUser("tenant-a", "alice", new PushPayload("title", "body", "tag", "/m/jobs"));

    verify(repository).deleteIfUnchanged(sub);
  }

  private final ConsolePushProperties properties = new ConsolePushProperties();
  private final ConsolePushSubscriptionMapper repository =
      mock(ConsolePushSubscriptionMapper.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ConsolePushSender sender =
      new ConsolePushSender(properties, repository, objectMapper);

  @Test
  @DisplayName("推送未开启时单发与广播都不访问订阅存储")
  void shouldSkipRepositoryWhenPushServiceIsDisabled() {
    PushPayload payload = new PushPayload("title", "body", "tag", "/m/jobs");

    sender.sendToUser("tenant-a", "alice", payload);
    sender.broadcastToTenant("tenant-a", payload);

    verifyNoInteractions(repository);
  }

  @Test
  @DisplayName("开启推送后密钥非法导致初始化失败时清空推送服务实例")
  void shouldClearPushServiceWhenEnabledInitFails() throws Exception {
    properties.setEnabled(true);
    properties.setPublicKey("not-a-public-key");
    properties.setPrivateKey("not-a-private-key");

    sender.init();

    assertThat(pushServiceRef(sender).get()).isNull();
  }

  @Test
  @DisplayName("订阅信息有效时经推送服务下发并刷新最近推送时间")
  void shouldSendNotificationThroughConfiguredPushService() throws Exception {
    ManagedWebPushClient service = mock(ManagedWebPushClient.class);
    Response response = mock(Response.class);
    when(response.getStatusCode()).thenReturn(201);
    when(service.send(any(Notification.class)))
        .thenReturn(CompletableFuture.completedFuture(response));
    pushServiceRef(sender).set(service);
    ConsolePushSubscriptionEntity sub = subscription();
    when(repository.findByTenantAndUser("tenant-a", "alice")).thenReturn(List.of(sub));

    sender.sendToUser("tenant-a", "alice", new PushPayload("title", null, null, null));

    verify(service).send(any(Notification.class));
    verify(repository).touchIfUnchanged(any(), any());
  }

  @Test
  @DisplayName("推送异常日志不包含 endpoint 的 userinfo 和 query 凭据")
  void shouldMaskEndpointSecretsFromPushFailureLog() throws Exception {
    ManagedWebPushClient service = mock(ManagedWebPushClient.class);
    when(service.send(any(Notification.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException(
            "failed for https://push-user:push-password@push.example/send?token=endpoint-secret")));
    pushServiceRef(sender).set(service);
    when(repository.findByTenantAndUser("tenant-a", "alice")).thenReturn(List.of(subscription()));

    Logger logger = (Logger) LoggerFactory.getLogger(ConsolePushSender.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      sender.sendToUser("tenant-a", "alice", new PushPayload("title", null, null, null));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    String logged = appender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .reduce("", (left, right) -> left + right);
    assertThat(logged).doesNotContain("push-user", "push-password", "endpoint-secret");
  }

  @Test
  @DisplayName("查找订阅期间推送服务被清空则不再发送")
  void shouldSkipSendWhenPushServiceIsClearedAfterLookup() throws Exception {
    ManagedWebPushClient service = mock(ManagedWebPushClient.class);
    AtomicReference<ManagedWebPushClient> ref = pushServiceRef(sender);
    ref.set(service);
    when(repository.findByTenantAndUser("tenant-a", "alice")).thenAnswer(invocation -> {
      ref.set(null);
      return List.of(subscription());
    });

    sender.sendToUser("tenant-a", "alice", new PushPayload("title", "body", "tag", "/m/jobs"));

    verify(service, never()).send(any(Notification.class));
  }

  private static ConsolePushSubscriptionEntity subscription() throws Exception {
    var sub =
        PushDeviceFixtures.device("tenant-a", "alice", "https://fcm.googleapis.com/fcm/send/test");
    sub.setId(42L);
    return sub;
  }

  @SuppressWarnings("unchecked")
  private static AtomicReference<ManagedWebPushClient> pushServiceRef(ConsolePushSender sender)
      throws Exception {
    Field field = ConsolePushSender.class.getDeclaredField("pushService");
    field.setAccessible(true);
    return (AtomicReference<ManagedWebPushClient>) field.get(sender);
  }
}
