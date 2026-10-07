package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import jakarta.mail.internet.MimeMessage;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

@DisplayName("邮件分发适配器:连接、读取与写入三类超时属性按渠道配置显式落到邮件会话")
class SmtpEmailDispatchChannelAdapterTest {

  @Test
  @DisplayName("构建邮件消息时把连接、读取与写入超时毫秒数写入邮件会话属性")
  void shouldSetExplicitSmtpTimeoutProperties() throws Exception {
    Environment environment = mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[0]);
    SmtpEmailDispatchChannelAdapter adapter =
        new SmtpEmailDispatchChannelAdapter(mock(DispatchFileContentResolver.class), environment);
    Map<String, Object> config = Map.of(
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
    MimeMessage message = (MimeMessage) invoke(
        adapter,
        "buildMimeMessage",
        new Class<?>[] {mailConfig.getClass(), DispatchCommand.class, String.class},
        mailConfig,
        command(config),
        "req-1");

    assertThat(message
            .getSession()
            .getProperty(SmtpEmailDispatchChannelAdapter.MAIL_SMTP_CONNECT_TIMEOUT_KEY))
        .isEqualTo("1234");
    assertThat(message
            .getSession()
            .getProperty(SmtpEmailDispatchChannelAdapter.MAIL_SMTP_READ_TIMEOUT_KEY))
        .isEqualTo("2345");
    assertThat(message
            .getSession()
            .getProperty(SmtpEmailDispatchChannelAdapter.MAIL_SMTP_WRITE_TIMEOUT_KEY))
        .isEqualTo("3456");
  }

  private static Object invoke(
      Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  private static DispatchCommand command(Map<String, Object> channelConfig) {
    DispatchPayload payload = new DispatchPayload(
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
