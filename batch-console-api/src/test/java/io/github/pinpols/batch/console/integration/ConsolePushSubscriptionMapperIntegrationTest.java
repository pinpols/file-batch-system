package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender;
import io.github.pinpols.batch.console.domain.notification.support.ManagedWebPushClient;
import io.github.pinpols.batch.console.domain.notification.support.PushDeviceFixtures;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import nl.martijndwars.webpush.Notification;
import org.asynchttpclient.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("推送订阅的租户隔离与凭据更新栅栏")
class ConsolePushSubscriptionMapperIntegrationTest extends AbstractIntegrationTest {
  @Autowired
  private ConsolePushSubscriptionMapper subscriptions;

  @DisplayName("迟到响应不能修改或删除已轮换的设备凭据")
  @ParameterizedTest
  @ValueSource(ints = {201, 410})
  @SuppressWarnings("unchecked")
  void shouldFenceLateResponse_whenDeviceCredentialsRotated(int status)
      throws Exception {
    String tenant = "push-rotation-" + UUID.randomUUID();
    String endpoint = "https://push.example.test/" + UUID.randomUUID();
    var original = PushDeviceFixtures.device(tenant, "alice", endpoint);
    var rotated = PushDeviceFixtures.device(tenant, "alice", endpoint);
    subscriptions.upsert(original);
    var snapshot = subscriptions.findByTenantAndUser(tenant, "alice").getFirst();
    var sender = new ConsolePushSender(
        new ConsolePushProperties(), subscriptions, JsonUtils.newDefaultMapper());
    var service = mock(ManagedWebPushClient.class);
    var response = mock(Response.class);
    when(response.getStatusCode()).thenReturn(status);
    when(service.send(any(Notification.class))).thenAnswer(call -> {
      subscriptions.upsert(rotated);
      return CompletableFuture.completedFuture(response);
    });
    ((AtomicReference<ManagedWebPushClient>) ReflectionTestUtils.getField(sender, "pushService"))
        .set(service);
    try {
      sender.sendToSubscription(
          snapshot, new ConsolePushSender.PushPayload("title", "body", "tag", "/m/jobs/10"));
      assertThat(subscriptions.findByTenantAndUser(tenant, "alice"))
          .singleElement()
          .satisfies(current -> {
            assertThat(current.getP256dhKey()).isEqualTo(rotated.getP256dhKey());
            assertThat(current.getLastPushedAt()).isNull();
          });
    } finally {
      subscriptions.deleteByEndpoint(tenant, "alice", endpoint);
    }
  }

  @DisplayName("删除过期订阅不影响相同端点的其它租户与用户")
  @Test
  void shouldPreserveOtherOwners_whenDeletingExpiredSubscription() {
    String tenantA = "push-a-" + UUID.randomUUID();
    String tenantB = "push-b-" + UUID.randomUUID();
    String endpoint = "https://push.example.test/" + UUID.randomUUID();
    try {
      subscriptions.upsert(subscription(tenantA, "alice", endpoint));
      subscriptions.upsert(subscription(tenantA, "bob", endpoint));
      subscriptions.upsert(subscription(tenantB, "alice", endpoint));

      assertThat(subscriptions.deleteByEndpoint(tenantA, "alice", endpoint)).isEqualTo(1);

      assertThat(subscriptions.findByTenantAndUser(tenantA, "alice")).isEmpty();
      assertThat(subscriptions.findByTenantAndUser(tenantA, "bob"))
          .singleElement()
          .extracting(ConsolePushSubscriptionEntity::getEndpoint)
          .isEqualTo(endpoint);
      assertThat(subscriptions.findByTenantAndUser(tenantB, "alice"))
          .singleElement()
          .extracting(ConsolePushSubscriptionEntity::getEndpoint)
          .isEqualTo(endpoint);
    } finally {
      subscriptions.deleteByEndpoint(tenantA, "alice", endpoint);
      subscriptions.deleteByEndpoint(tenantA, "bob", endpoint);
      subscriptions.deleteByEndpoint(tenantB, "alice", endpoint);
    }
  }

  private static ConsolePushSubscriptionEntity subscription(
      String tenant, String user, String endpoint) {
    ConsolePushSubscriptionEntity sub = new ConsolePushSubscriptionEntity();
    sub.setTenantId(tenant);
    sub.setUsername(user);
    sub.setEndpoint(endpoint);
    sub.setP256dhKey("fixture-key-not-used-for-delivery");
    sub.setAuthSecret("fixture-auth-not-used-for-delivery");
    return sub;
  }
}
