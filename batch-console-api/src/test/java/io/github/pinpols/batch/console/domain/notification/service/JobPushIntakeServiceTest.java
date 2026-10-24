package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.JobPushDeliveryMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JobPushIntakeServiceTest {
  @ParameterizedTest
  @ValueSource(strings={"SUCCESS_DRY_RUN","FAILED_DRY_RUN"})
  void dryRunTerminalStateCannotCreateFormalIntentWhenFlagIsFalse(String status) {
    var job=job();job.put("instanceStatus",status);
    when(subscriptions.findByTenantAndUser("ta","alice")).thenReturn(List.of(device()));
    service.accept("ta",KEY,job);
    verify(deliveries).insertDecision(eq("ta"),eq(KEY),eq(10L),eq("alice"),eq("DRY_RUN"),anyString(),anyString());
    verifyNoInteractions(subscriptions);
  }
  private final ConsolePushProperties properties = new ConsolePushProperties();
  private final ConsolePushSubscriptionMapper subscriptions = mock(ConsolePushSubscriptionMapper.class);
  private final JobPushDeliveryMapper deliveries = mock(JobPushDeliveryMapper.class);
  private final JobPushIntakeService service = new JobPushIntakeService(properties, subscriptions, deliveries);
  private static final String KEY = "ta:job:10:terminal:SUCCESS";

  @BeforeEach
  void setup() {
    properties.setEnabled(true);
    properties.getJobNotify().setEnabled(true);
    when(deliveries.insertDecision(anyString(),anyString(),anyLong(),any(),anyString(),anyString(),anyString())).thenReturn(1);
    when(deliveries.insertDevice(anyString(),anyString(),anyLong(),anyString(),anyLong(),anyString(),anyString(),anyString())).thenReturn(1);
  }

  @Test
  void disabledPushRecordsDecisionWithoutLookingUpDevices() {
    properties.setEnabled(false);
    service.accept("ta",KEY,job());
    verify(deliveries).insertDecision(eq("ta"),eq(KEY),eq(10L),eq("alice"),eq("DISABLED"),eq("JOB_SUCCESS"),anyString());
    verifyNoInteractions(subscriptions);
    verify(deliveries,never()).insertDevice(any(),any(),anyLong(),any(),any(),any(),any(),any());
  }

  @Test
  void dryRunHasNoFormalDeviceIntent() {
    var job=job(); job.put("dryRun",true);
    service.accept("ta",KEY,job);
    verify(deliveries).insertDecision(eq("ta"),eq(KEY),eq(10L),eq("alice"),eq("DRY_RUN"),anyString(),anyString());
    verifyNoInteractions(subscriptions);
  }

  @Test
  void missingOperatorIsExplicitAndDoesNotExpandCurrentUsers() {
    var job=job(); job.put("operatorId",null);
    service.accept("ta",KEY,job);
    verify(deliveries).insertDecision(eq("ta"),eq(KEY),eq(10L),isNull(),eq("NO_OPERATOR"),anyString(),anyString());
    verifyNoInteractions(subscriptions);
  }

  @Test
  void noSubscriptionsIsAnExplicitFirstDecision() {
    when(subscriptions.findByTenantAndUser("ta","alice")).thenReturn(List.of());
    service.accept("ta",KEY,job());
    verify(deliveries).insertDecision(eq("ta"),eq(KEY),eq(10L),eq("alice"),eq("NO_SUBSCRIPTIONS"),anyString(),anyString());
  }

  @Test
  void deviceIntentUsesFrozenOwnerAndDoesNotStoreEndpointOrKeysInPayload() {
    var device=device();
    when(subscriptions.findByTenantAndUser("ta","alice")).thenReturn(List.of(device));
    service.accept("ta",KEY,job());
    var payload=org.mockito.ArgumentCaptor.forClass(String.class);
    verify(deliveries).insertDevice(eq("ta"),eq(KEY),eq(10L),eq("alice"),eq(1L),
        eq(JobPushIntakeService.fingerprint(device)),eq("JOB_SUCCESS"),payload.capture());
    assertThat(payload.getValue()).doesNotContain(device.getEndpoint(),device.getP256dhKey(),device.getAuthSecret());
    assertThat(JsonUtils.fromJson(payload.getValue(),
        io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.PushPayload.class).title())
        .isEqualTo("任务 IMPORT 已成功");
    assertThat(JsonUtils.fromJson(payload.getValue(),
        io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.PushPayload.class).url())
        .isEqualTo("/m/jobs/10?tenantId=ta");
  }

  @Test
  void deviceFingerprintIgnoresRefreshTimeButChangesWithKeysOrOwner() {
    var device=device(); String original=JobPushIntakeService.fingerprint(device);
    device.setLastSeenAt(Instant.now());
    assertThat(JobPushIntakeService.fingerprint(device)).isEqualTo(original).hasSize(64);
    device.setAuthSecret("rotated-auth");
    assertThat(JobPushIntakeService.fingerprint(device)).isNotEqualTo(original);
    device=device();device.setUsername("bob");
    assertThat(JobPushIntakeService.fingerprint(device)).isNotEqualTo(original);
  }

  @Test
  void aDeviceFromAnotherTenantFailsBeforeAnyIntentWrite() {
    var wrong=device();wrong.setTenantId("tb");
    when(subscriptions.findByTenantAndUser("ta","alice")).thenReturn(List.of(wrong));
    assertThatThrownBy(()->service.accept("ta",KEY,job())).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(deliveries);
  }

  private static Map<String,Object> job() {
    return new LinkedHashMap<>(Map.of("jobInstanceId",10L,"jobCode","IMPORT","operatorId","alice",
        "instanceStatus","SUCCESS","dryRun",false));
  }

  private static ConsolePushSubscriptionEntity device() {
    var device=new ConsolePushSubscriptionEntity();device.setId(1L);device.setTenantId("ta");
    device.setUsername("alice");device.setEndpoint("https://push.example.test/device-one");
    device.setP256dhKey("secret-public-device-key");device.setAuthSecret("secret-auth-value");return device;
  }
}
