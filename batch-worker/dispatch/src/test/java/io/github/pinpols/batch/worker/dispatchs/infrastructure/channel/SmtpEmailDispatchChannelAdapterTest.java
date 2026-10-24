package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import jakarta.mail.internet.MimeMessage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

  @Test
  @DisplayName("SMTP 已收到 DATA 但最终确认响应丢失时报告外部结果不确定")
  void shouldReportUnknownOutcomeWhenServerAcceptsDataAndDropsFinalResponse() throws Exception {
    DispatchFileContentResolver fileContentResolver = mock(DispatchFileContentResolver.class);
    when(fileContentResolver.openInputStream(anyMap()))
        .thenReturn(new ByteArrayInputStream("mail-payload".getBytes(StandardCharsets.UTF_8)));
    Environment environment = mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[0]);
    SmtpEmailDispatchChannelAdapter adapter =
        new SmtpEmailDispatchChannelAdapter(fileContentResolver, environment);

    try (AcceptThenDropSmtpServer smtpServer = new AcceptThenDropSmtpServer()) {
      Map<String, Object> config = Map.of(
          "smtp_host", "127.0.0.1",
          "smtp_port", smtpServer.port(),
          "smtp_username", "sender@example.test",
          "smtp_password", "secret",
          "smtp_starttls", false,
          "mail_from", "sender@example.test",
          "mail_to", "receiver@example.test");

      DispatchResult result = adapter.dispatch(command(config));

      assertThat(result.success()).isFalse();
      assertThat(result.externalOutcomeUnknown()).isTrue();
      assertThat(result.message()).contains("EXTERNAL_OUTCOME_UNKNOWN");
      assertThat(smtpServer.awaitAcceptedMessages()).isEqualTo(1);
    }
  }

  private static final class AcceptThenDropSmtpServer implements AutoCloseable {

    private final ServerSocket server = new ServerSocket(0);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger acceptedMessages = new AtomicInteger();

    private AcceptThenDropSmtpServer() throws Exception {
      executor.submit(this::serveOneMessage);
    }

    private int port() {
      return server.getLocalPort();
    }

    private int awaitAcceptedMessages() throws Exception {
      executor.shutdown();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      return acceptedMessages.get();
    }

    private void serveOneMessage() {
      try (Socket socket = server.accept()) {
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        PrintWriter writer = new PrintWriter(
            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
        reply(writer, "220 localhost ESMTP ready");
        String line;
        while ((line = reader.readLine()) != null) {
          String command = line.toUpperCase(Locale.ROOT);
          if (command.startsWith("EHLO") || command.startsWith("HELO")) {
            reply(writer, "250-localhost");
            reply(writer, "250-AUTH PLAIN LOGIN");
            reply(writer, "250 SIZE 10485760");
          } else if (command.startsWith("AUTH ")) {
            reply(writer, "235 2.7.0 authenticated");
          } else if (command.startsWith("MAIL FROM:") || command.startsWith("RCPT TO:")) {
            reply(writer, "250 2.1.0 accepted");
          } else if ("DATA".equals(command)) {
            reply(writer, "354 end with dot");
            while ((line = reader.readLine()) != null && !".".equals(line)) {
              // 非 Worker 节点完成整个 DAG 时，立即结束 Workflow。
            }
            if (".".equals(line)) {
              acceptedMessages.incrementAndGet();
            }
            socket.setSoLinger(true, 0);
            return;
          } else if ("QUIT".equals(command)) {
            reply(writer, "221 2.0.0 closing");
            return;
          } else {
            reply(writer, "250 2.0.0 ok");
          }
        }
      } catch (Exception ignored) {
        // 创建 Workflow Run 时捕获的不可变定义输入。
      }
    }

    private static void reply(PrintWriter writer, String line) {
      writer.print(line + "\r\n");
      writer.flush();
    }

    @Override
    public void close() throws Exception {
      server.close();
      executor.shutdownNow();
    }
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
