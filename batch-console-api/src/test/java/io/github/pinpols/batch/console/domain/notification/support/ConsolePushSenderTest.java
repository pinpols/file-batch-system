package io.github.pinpols.batch.console.domain.notification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.PushPayload;
import java.lang.reflect.Field;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushAsyncService;
import org.asynchttpclient.Response;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("推送发送器: 开关判定, 初始化失败兜底与投递目标查找的生命周期")
class ConsolePushSenderTest {

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
    PushAsyncService service = mock(PushAsyncService.class);
    Response response = mock(Response.class);
    when(response.getStatusCode()).thenReturn(201);
    when(service.send(any(Notification.class)))
        .thenReturn(CompletableFuture.completedFuture(response));
    pushServiceRef(sender).set(service);
    ConsolePushSubscriptionEntity sub = subscription();
    when(repository.findByTenantAndUser("tenant-a", "alice")).thenReturn(List.of(sub));

    sender.sendToUser("tenant-a", "alice", new PushPayload("title", null, null, null));

    verify(service).send(any(Notification.class));
    verify(repository).touchLastPushedAt(any(), any());
  }

  @Test
  @DisplayName("查找订阅期间推送服务被清空则不再发送")
  void shouldSkipSendWhenPushServiceIsClearedAfterLookup() throws Exception {
    PushAsyncService service = mock(PushAsyncService.class);
    AtomicReference<PushAsyncService> ref = pushServiceRef(sender);
    ref.set(service);
    when(repository.findByTenantAndUser("tenant-a", "alice")).thenAnswer(invocation -> {
      ref.set(null);
      return List.of(subscription());
    });

    sender.sendToUser("tenant-a", "alice", new PushPayload("title", "body", "tag", "/m/jobs"));

    verify(service, never()).send(any(Notification.class));
  }

  private static ConsolePushSubscriptionEntity subscription() throws Exception {
    ConsolePushSubscriptionEntity sub = new ConsolePushSubscriptionEntity();
    sub.setId(42L);
    sub.setTenantId("tenant-a");
    sub.setUsername("alice");
    sub.setEndpoint("https://fcm.googleapis.com/fcm/send/test");
    sub.setP256dhKey(validP256dhKey());
    sub.setAuthSecret(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]));
    return sub;
  }

  private static String validP256dhKey() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPairGenerator generator =
        KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
    generator.initialize(
        new ECGenParameterSpec("prime256v1"), new SecureRandom(new byte[] {1, 2, 3}));
    ECPublicKey publicKey = (ECPublicKey) generator.generateKeyPair().getPublic();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(publicKey.getQ().getEncoded(false));
  }

  @SuppressWarnings("unchecked")
  private static AtomicReference<PushAsyncService> pushServiceRef(ConsolePushSender sender)
      throws Exception {
    Field field = ConsolePushSender.class.getDeclaredField("pushService");
    field.setAccessible(true);
    return (AtomicReference<PushAsyncService>) field.get(sender);
  }
}
