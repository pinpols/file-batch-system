package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.NotificationProperties;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class EmailNotificationSenderTest {

  @Mock private JavaMailSender mailSender;

  @SuppressWarnings("unchecked")
  @Mock
  private ObjectProvider<JavaMailSender> mailSenderProvider;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private EmailNotificationSender sender;

  @BeforeEach
  void setUp() {
    // 默认空白名单 = 不限制域名,既有用例不受影响。
    sender =
        new EmailNotificationSender(mailSenderProvider, objectMapper, new NotificationProperties());
  }

  private NotificationMessage message(String configJson) {
    WebhookEventPayload payload =
        new WebhookEventPayload("t1", "JOB_FAILED", "stream", "cursor", Instant.EPOCH, null);
    return new NotificationMessage("t1", "ch-email", "EMAIL", configJson, payload, "{\"k\":\"v\"}");
  }

  @Test
  void supports_isCaseInsensitive_forEmailOnly() {
    // assert
    assertThat(sender.supports("EMAIL")).isTrue();
    assertThat(sender.supports("email")).isTrue();
    assertThat(sender.supports("Email")).isTrue();
    assertThat(sender.supports("WEBHOOK")).isFalse();
    assertThat(sender.supports(null)).isFalse();
  }

  @Test
  void send_returnsFailure_andDoesNotSend_whenRecipientsMissing() {
    // act
    WebhookDeliveryResult result = sender.send(message("{\"subject\":\"hi\"}"));

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).isEqualTo("missing email recipients");
    verify(mailSenderProvider, never()).getIfAvailable();
    verify(mailSender, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  void send_returnsFailure_whenMailSenderNotConfigured() {
    // arrange
    when(mailSenderProvider.getIfAvailable()).thenReturn(null);

    // act
    WebhookDeliveryResult result = sender.send(message("{\"to\":\"a@x.com\"}"));

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).isEqualTo("mail not configured");
    verify(mailSender, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  void send_deliversMail_andReturnsOk_onHappyPath() {
    // arrange
    when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);

    // act
    WebhookDeliveryResult result =
        sender.send(
            message("{\"to\":\"a@x.com, b@x.com\",\"subject\":\"Alert\",\"from\":\"ops@x.com\"}"));

    // assert
    assertThat(result.success()).isTrue();
    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender).send(captor.capture());
    SimpleMailMessage sent = captor.getValue();
    assertThat(sent.getTo()).containsExactly("a@x.com", "b@x.com");
    assertThat(sent.getSubject()).isEqualTo("Alert");
    assertThat(sent.getFrom()).isEqualTo("ops@x.com");
    assertThat(sent.getText()).contains("JOB_FAILED");
  }

  @Test
  void send_defaultsSubjectFromEventType_whenSubjectMissing() {
    // arrange
    when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);

    // act
    WebhookDeliveryResult result = sender.send(message("{\"to\":\"a@x.com\"}"));

    // assert
    assertThat(result.success()).isTrue();
    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender).send(captor.capture());
    assertThat(captor.getValue().getSubject()).contains("JOB_FAILED");
  }

  @Test
  void send_returnsFailure_whenMailSenderThrows() {
    // arrange
    when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
    MailException boom = new MailSendException("smtp down");
    doThrow(boom).when(mailSender).send(any(SimpleMailMessage.class));

    // act
    WebhookDeliveryResult result = sender.send(message("{\"to\":\"a@x.com\"}"));

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).isEqualTo("MailSendException");
  }
}
