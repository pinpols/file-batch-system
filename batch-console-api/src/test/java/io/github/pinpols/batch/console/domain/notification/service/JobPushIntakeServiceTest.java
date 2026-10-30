package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.JobPushDeliveryMapper;
import io.github.pinpols.batch.console.domain.notification.param.JobPushDecisionInsertParam;
import io.github.pinpols.batch.console.domain.notification.param.JobPushDeviceInsertParam;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("作业推送的接纳决策与接收设备冻结")
@ExtendWith(MockitoExtension.class)
class JobPushIntakeServiceTest {
  @DisplayName("预演终态即使标志为假也不创建正式意图")
  @ParameterizedTest
  @ValueSource(strings = {"SUCCESS_DRY_RUN", "FAILED_DRY_RUN"})
  void shouldRejectFormalIntent_whenTerminalStateIsDryRun(String status) {
    when(deliveries.insertDecision(any(JobPushDecisionInsertParam.class))).thenReturn(1);
    var job = job();
    job.put("instanceStatus", status);
    service.accept("ta", KEY, job);
    verifyDecision("DRY_RUN", "JOB_" + status);
    verifyNoInteractions(subscriptions);
  }

  private final ConsolePushProperties properties = new ConsolePushProperties();

  @Mock
  private ConsolePushSubscriptionMapper subscriptions;

  @Mock
  private JobPushDeliveryMapper deliveries;

  private JobPushIntakeService service;
  private static final String KEY = "ta:job:10:terminal:SUCCESS";

  @BeforeEach
  void setup() {
    service = new JobPushIntakeService(properties, subscriptions, deliveries);
    properties.setEnabled(true);
    properties.getJobNotify().setEnabled(true);
  }

  @DisplayName("推送停用时记录决策且不查询设备")
  @Test
  void shouldRecordDisabledDecision_whenPushDisabled() {
    when(deliveries.insertDecision(any(JobPushDecisionInsertParam.class))).thenReturn(1);
    properties.setEnabled(false);
    service.accept("ta", KEY, job());
    verifyDecision("DISABLED", "JOB_SUCCESS");
    verifyNoInteractions(subscriptions);
    verify(deliveries, never()).insertDevice(any(JobPushDeviceInsertParam.class));
  }

  @DisplayName("预演不创建正式设备意图")
  @Test
  void shouldAvoidFormalIntent_whenDryRun() {
    when(deliveries.insertDecision(any(JobPushDecisionInsertParam.class))).thenReturn(1);
    var job = job();
    job.put("dryRun", true);
    service.accept("ta", KEY, job);
    verifyDecision("DRY_RUN", "JOB_SUCCESS");
    verifyNoInteractions(subscriptions);
  }

  @DisplayName("操作员缺失时显式记录且不扩散到当前用户")
  @Test
  void shouldRecordMissingOperator_whenRecipientUnknown() {
    when(deliveries.insertDecision(any(JobPushDecisionInsertParam.class))).thenReturn(1);
    var job = job();
    job.put("operatorId", null);
    service.accept("ta", KEY, job);
    verifyDecision("NO_OPERATOR", "JOB_SUCCESS");
    verifyNoInteractions(subscriptions);
  }

  @DisplayName("首次无订阅时记录明确决策")
  @Test
  void shouldRecordNoSubscriptions_whenFirstDecisionHasNoDevice() {
    when(deliveries.insertDecision(any(JobPushDecisionInsertParam.class))).thenReturn(1);
    when(subscriptions.findByTenantAndUser("ta", "alice")).thenReturn(List.of());
    service.accept("ta", KEY, job());
    verifyDecision("NO_SUBSCRIPTIONS", "JOB_SUCCESS");
  }

  @DisplayName("设备意图冻结所有者且载荷不保存端点或密钥")
  @Test
  void shouldFreezeOwnerWithoutCredentials_whenCreatingDeviceIntent() {
    when(deliveries.insertDevice(any(JobPushDeviceInsertParam.class))).thenReturn(1);
    when(deliveries.insertDecision(any(JobPushDecisionInsertParam.class))).thenReturn(1);
    var device = device();
    when(subscriptions.findByTenantAndUser("ta", "alice")).thenReturn(List.of(device));
    service.accept("ta", KEY, job());
    var deviceParam = ArgumentCaptor.forClass(JobPushDeviceInsertParam.class);
    verify(deliveries).insertDevice(deviceParam.capture());
    assertThat(deviceParam.getValue().tenantId()).isEqualTo("ta");
    assertThat(deviceParam.getValue().eventKey()).isEqualTo(KEY);
    assertThat(deviceParam.getValue().jobId()).isEqualTo(10L);
    assertThat(deviceParam.getValue().operator()).isEqualTo("alice");
    assertThat(deviceParam.getValue().subscriptionId()).isEqualTo(1L);
    assertThat(deviceParam.getValue().fingerprint())
        .isEqualTo(JobPushIntakeService.fingerprint(device));
    assertThat(deviceParam.getValue().eventType()).isEqualTo("JOB_SUCCESS");
    assertThat(deviceParam.getValue().payload())
        .doesNotContain(device.getEndpoint(), device.getP256dhKey(), device.getAuthSecret());
    assertThat(JsonUtils.fromJson(
                deviceParam.getValue().payload(), ConsolePushSender.PushPayload.class)
            .title())
        .isEqualTo("任务 IMPORT 已成功");
    assertThat(JsonUtils.fromJson(
                deviceParam.getValue().payload(), ConsolePushSender.PushPayload.class)
            .url())
        .isEqualTo("/m/jobs/10?tenantId=ta");
  }

  @DisplayName("设备指纹忽略刷新时间并随密钥或所有者变化")
  @Test
  void shouldKeepFingerprintStable_whenOnlyRefreshTimeChanges() {
    var device = device();
    String original = JobPushIntakeService.fingerprint(device);
    device.setLastSeenAt(Instant.now());
    assertThat(JobPushIntakeService.fingerprint(device)).isEqualTo(original).hasSize(64);
    device.setAuthSecret("rotated-auth");
    assertThat(JobPushIntakeService.fingerprint(device)).isNotEqualTo(original);
    device = device();
    device.setUsername("bob");
    assertThat(JobPushIntakeService.fingerprint(device)).isNotEqualTo(original);
  }

  @DisplayName("跨租户设备在写入推送意图前被拒绝")
  @Test
  void shouldRejectDeviceBeforeWrite_whenTenantDiffers() {
    var wrong = device();
    wrong.setTenantId("tb");
    when(subscriptions.findByTenantAndUser("ta", "alice")).thenReturn(List.of(wrong));
    assertThatThrownBy(() -> service.accept("ta", KEY, job()))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(deliveries);
  }

  private static Map<String, Object> job() {
    return new LinkedHashMap<>(Map.of(
        "jobInstanceId",
        10L,
        "jobCode",
        "IMPORT",
        "operatorId",
        "alice",
        "instanceStatus",
        "SUCCESS",
        "dryRun",
        false));
  }

  private static ConsolePushSubscriptionEntity device() {
    var device = new ConsolePushSubscriptionEntity();
    device.setId(1L);
    device.setTenantId("ta");
    device.setUsername("alice");
    device.setEndpoint("https://push.example.test/device-one");
    device.setP256dhKey("secret-public-device-key");
    device.setAuthSecret("secret-auth-value");
    return device;
  }

  private void verifyDecision(String expectedDecision, String expectedEventType) {
    var decisionParam = ArgumentCaptor.forClass(JobPushDecisionInsertParam.class);
    verify(deliveries).insertDecision(decisionParam.capture());
    assertThat(decisionParam.getValue().tenantId()).isEqualTo("ta");
    assertThat(decisionParam.getValue().eventKey()).isEqualTo(KEY);
    assertThat(decisionParam.getValue().jobId()).isEqualTo(10L);
    assertThat(decisionParam.getValue().operator())
        .isEqualTo("NO_OPERATOR".equals(expectedDecision) ? null : "alice");
    assertThat(decisionParam.getValue().decision()).isEqualTo(expectedDecision);
    assertThat(decisionParam.getValue().eventType()).isEqualTo(expectedEventType);
    assertThat(decisionParam.getValue().payload()).isNotBlank();
  }
}
