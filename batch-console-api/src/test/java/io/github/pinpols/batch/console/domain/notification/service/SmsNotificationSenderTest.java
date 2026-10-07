package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.SmsProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("短信通知发送器: 供应商选择, 手机号解析与重复编码校验")
class SmsNotificationSenderTest {

  @Mock
  private SmsProvider aliyunProvider;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    when(aliyunProvider.providerCode()).thenReturn("aliyun");
  }

  private NotificationMessage message(String configJson) {
    WebhookEventPayload payload =
        new WebhookEventPayload("t1", "JOB_FAILED", "s", "c", Instant.EPOCH, null);
    return new NotificationMessage("t1", "ch-sms", "SMS", configJson, payload, "{}");
  }

  private SmsNotificationSender newSender(String provider) {
    SmsProperties props = new SmsProperties();
    props.setProvider(provider);
    return new SmsNotificationSender(List.of(aliyunProvider), props, objectMapper);
  }

  @Test
  @DisplayName("只识别短信渠道且大小写不敏感, 其它渠道不支持")
  void shouldSupportSmsChannelOnly() {
    assertThat(newSender("aliyun").supports("SMS")).isTrue();
    assertThat(newSender("aliyun").supports("sms")).isTrue();
    assertThat(newSender("aliyun").supports("EMAIL")).isFalse();
  }

  @Test
  @DisplayName("缺少手机号时直接失败, 不委托任何供应商")
  void missingPhoneNumbers_failsWithoutDelegating() {
    WebhookDeliveryResult r = newSender("aliyun").send(message("{\"signName\":\"x\"}"));
    assertThat(r.success()).isFalse();
    assertThat(r.errorSummary()).isEqualTo("missing sms phoneNumbers");
    verify(aliyunProvider, never()).send(anyList(), any());
  }

  @Test
  @DisplayName("供应商配置为空时失败, 并提示未配置")
  void providerNone_fails() {
    WebhookDeliveryResult r =
        newSender("none").send(message("{\"phoneNumbers\":\"+8613800000000\"}"));
    assertThat(r.success()).isFalse();
    assertThat(r.errorSummary()).isEqualTo("sms provider not configured");
  }

  @Test
  @DisplayName("无匹配的供应商实现时失败, 错误摘要提示不可用")
  void noMatchingProviderImpl_fails() {
    WebhookDeliveryResult r =
        newSender("tencent").send(message("{\"phoneNumbers\":\"+8613800000000\"}"));
    assertThat(r.success()).isFalse();
    assertThat(r.errorSummary()).contains("sms provider not available");
  }

  @Test
  @DisplayName("供应商编码匹配时, 按半角逗号拆分手机号并原样委托")
  void shouldDelegate_whenProviderMatches() {
    when(aliyunProvider.send(anyList(), any())).thenReturn(WebhookDeliveryResult.ok());
    WebhookDeliveryResult r =
        newSender("aliyun").send(message("{\"phoneNumbers\":\"+8613800000000,+8613800000001\"}"));
    assertThat(r.success()).isTrue();
    verify(aliyunProvider)
        .send(
            List.of("+8613800000000", "+8613800000001"),
            message("{\"phoneNumbers\":\"+8613800000000,+8613800000001\"}"));
  }

  @Test
  @DisplayName("供应商编码重复时构造阶段即失败, 提示冲突的编码")
  void shouldFailFast_whenProviderCodeDuplicated() {
    SmsProvider duplicate = org.mockito.Mockito.mock(SmsProvider.class);
    when(duplicate.providerCode()).thenReturn("ALIYUN");

    assertThatThrownBy(() -> new SmsNotificationSender(
            List.of(aliyunProvider, duplicate), new SmsProperties(), objectMapper))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate SmsProvider providerCode: aliyun");
  }
}
