package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import jakarta.mail.internet.MimeMessage;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class SmtpEmailDispatchChannelAdapterTest {

  @Test
  void shouldSetExplicitSmtpTimeoutProperties() throws Exception {
    Environment environment = mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[0]);
    SmtpEmailDispatchChannelAdapter adapter =
        new SmtpEmailDispatchChannelAdapter(mock(DispatchFileContentResolver.class), environment);
    Map<String, Object> config =
        Map.of(
            "smtp_host",
            "smtp.example.test",
            "smtp_port",
            587,
            "smtp_username",
            "sender@example.test",
            "smtp_password",
            "secret",
            "mail_to",
            "receiver@example.test",
            "smtp_connection_timeout_millis",
            1234,
            "smtp_timeout_millis",
            2345,
            "smtp_write_timeout_millis",
            3456);

    Object mailConfig = invoke(adapter, "resolveMailConfig", new Class<?>[] {Map.class}, config);
    MimeMessage message =
        (MimeMessage)
            invoke(
                adapter,
                "buildMimeMessage",
                new Class<?>[] {mailConfig.getClass(), DispatchCommand.class, String.class},
                mailConfig,
                command(config),
                "req-1");

    assertThat(message.getSession().getProperty("mail.smtp.connectiontimeout")).isEqualTo("1234");
    assertThat(message.getSession().getProperty("mail.smtp.timeout")).isEqualTo("2345");
    assertThat(message.getSession().getProperty("mail.smtp.writetimeout")).isEqualTo("3456");
  }

  private static Object invoke(
      Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  private static DispatchCommand command(Map<String, Object> channelConfig) {
    DispatchPayload payload =
        new DispatchPayload(
            "file-1",
            "FILE_A",
            "EMAIL_CH",
            "target",
            "req-1",
            "rcpt-1",
            true,
            false,
            "NORMAL",
            Map.of());
    return new DispatchCommand(
        "t1",
        "trace-1",
        Map.of("id", 1L, "file_name", "a.csv", "mime_type", "text/csv"),
        channelConfig,
        payload);
  }
}
