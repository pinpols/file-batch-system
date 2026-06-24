package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.SmsProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SmsNotificationSenderTest {

  @Mock private SmsProvider aliyunProvider;
  private final ObjectMapper objectMapper = new ObjectMapper();

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
  void supportsSmsOnly() {
    assertThat(newSender("aliyun").supports("SMS")).isTrue();
    assertThat(newSender("aliyun").supports("sms")).isTrue();
    assertThat(newSender("aliyun").supports("EMAIL")).isFalse();
  }

  @Test
  void missingPhoneNumbers_failsWithoutDelegating() {
    WebhookDeliveryResult r = newSender("aliyun").send(message("{\"signName\":\"x\"}"));
    assertThat(r.success()).isFalse();
    assertThat(r.errorSummary()).isEqualTo("missing sms phoneNumbers");
    verify(aliyunProvider, never()).send(anyList(), any());
  }

  @Test
  void providerNone_fails() {
    WebhookDeliveryResult r =
        newSender("none").send(message("{\"phoneNumbers\":\"+8613800000000\"}"));
    assertThat(r.success()).isFalse();
    assertThat(r.errorSummary()).isEqualTo("sms provider not configured");
  }

  @Test
  void noMatchingProviderImpl_fails() {
    when(aliyunProvider.supports("tencent")).thenReturn(false);
    WebhookDeliveryResult r =
        newSender("tencent").send(message("{\"phoneNumbers\":\"+8613800000000\"}"));
    assertThat(r.success()).isFalse();
    assertThat(r.errorSummary()).contains("sms provider not available");
  }

  @Test
  void delegatesToMatchingProvider() {
    when(aliyunProvider.supports("aliyun")).thenReturn(true);
    when(aliyunProvider.send(anyList(), any())).thenReturn(WebhookDeliveryResult.ok());
    WebhookDeliveryResult r =
        newSender("aliyun").send(message("{\"phoneNumbers\":\"+8613800000000,+8613800000001\"}"));
    assertThat(r.success()).isTrue();
    verify(aliyunProvider)
        .send(
            List.of("+8613800000000", "+8613800000001"),
            message("{\"phoneNumbers\":\"+8613800000000,+8613800000001\"}"));
  }
}
