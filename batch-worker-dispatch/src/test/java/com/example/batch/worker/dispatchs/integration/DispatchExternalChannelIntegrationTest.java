package com.example.batch.worker.dispatchs.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.dispatchs.BatchWorkerDispatchApplication;
import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchChannelGateway;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchCommand;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    classes = BatchWorkerDispatchApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DispatchExternalChannelIntegrationTest extends AbstractIntegrationTest {

  private static final DockerImageName SFTP_IMAGE = DockerImageName.parse("atmoz/sftp:alpine");
  private static final String SFTP_USER = "batch";
  private static final String SFTP_PASSWORD = "batch-pass";
  private static final String SMTP_USER = "sender@example.com";
  private static final String SMTP_PASSWORD = "smtp-secret";
  private static final String SMTP_RECIPIENT = "recipient@example.com";
  private static final long MAIL_WAIT_TIMEOUT_MILLIS = Duration.ofSeconds(10).toMillis();

  @Container
  private static final GenericContainer<?> SFTP_SERVER =
      new GenericContainer<>(SFTP_IMAGE)
          .withExposedPorts(22)
          .withCommand(SFTP_USER + ":" + SFTP_PASSWORD + ":::upload")
          .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

  private static final GreenMail GREEN_MAIL = new GreenMail(ServerSetupTest.SMTP);

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @BeforeAll
  static void startMailServer() {
    GREEN_MAIL.start();
    GREEN_MAIL.setUser(SMTP_USER, SMTP_USER, SMTP_PASSWORD);
    GREEN_MAIL.setUser(SMTP_RECIPIENT, SMTP_RECIPIENT, "recipient-pass");
  }

  @AfterAll
  static void stopMailServer() {
    GREEN_MAIL.stop();
  }

  @Autowired private DispatchChannelGateway gateway;

  @Test
  void shouldDispatchFileToRealMinioObjectStorage() throws Exception {
    String tenantId = "tenant-oss";
    String channelCode = "oss-" + UUID.randomUUID();
    String sourceObject = "input/" + UUID.randomUUID() + ".txt";
    String targetObject = "output-" + UUID.randomUUID() + ".txt";
    byte[] content = ("oss-payload-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

    MinioClient minioClient = minioClient();
    ensureMinioBucket(minioBucket());
    minioClient.putObject(
        PutObjectArgs.builder().bucket(minioBucket()).object(sourceObject).stream(
                new ByteArrayInputStream(content), content.length, 10 * 1024 * 1024)
            .contentType("text/plain")
            .build());

    DispatchResult result =
        dispatch(
            tenantId,
            Map.of(
                "tenant_id", tenantId,
                "channel_type", "OSS",
                "channel_code", channelCode,
                "oss_bucket", minioBucket(),
                "oss_object_name", targetObject),
            fileRecord(sourceObject, "OSS", minioBucket(), "input.txt", "text/plain"),
            payload("file-oss", channelCode, "req-oss", "rc-oss"));

    assertThat(result.success()).isTrue();
    assertThat(result.evidenceRef()).isEqualTo("oss://" + minioBucket() + "/" + targetObject);

    assertThat(
            minioClient.statObject(
                StatObjectArgs.builder().bucket(minioBucket()).object(targetObject).build()))
        .isNotNull();
    try (InputStream in =
        minioClient.getObject(
            GetObjectArgs.builder().bucket(minioBucket()).object(targetObject).build())) {
      assertThat(in.readAllBytes()).isEqualTo(content);
    }
  }

  @Test
  void shouldDispatchFileToRealSftpServer() throws Exception {
    String tenantId = "tenant-sftp";
    String channelCode = "sftp-" + UUID.randomUUID();
    Path localFile = Files.createTempFile("dispatch-sftp-", ".txt");
    byte[] content = ("sftp-payload-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    Files.write(localFile, content);
    String remoteFileName = "dispatch-" + UUID.randomUUID() + ".txt";
    String remotePath = "/home/" + SFTP_USER + "/upload/" + remoteFileName;

    DispatchResult result =
        dispatch(
            tenantId,
            Map.ofEntries(
                Map.entry("tenant_id", tenantId),
                Map.entry("channel_type", "SFTP"),
                Map.entry("channel_code", channelCode),
                Map.entry("sftp_host", SFTP_SERVER.getHost()),
                Map.entry("sftp_port", SFTP_SERVER.getMappedPort(22)),
                Map.entry("sftp_user", SFTP_USER),
                Map.entry("sftp_password", SFTP_PASSWORD),
                Map.entry("sftp_remote_directory", "/upload/"),
                Map.entry("sftp_remote_file_name", remoteFileName),
                Map.entry("sftp_strict_host_key_checking", "no")),
            fileRecord(localFile.toString(), "LOCAL", null, "source.txt", "text/plain"),
            payload("file-sftp", channelCode, "req-sftp", "rc-sftp"));

    assertThat(result.success()).isTrue();
    assertThat(result.evidenceRef()).contains("sftp://");

    ExecResult exec =
        SFTP_SERVER.execInContainer("sh", "-lc", "test -f " + remotePath + " && cat " + remotePath);
    assertThat(exec.getExitCode()).isZero();
    assertThat(exec.getStdout()).isEqualTo(new String(content, StandardCharsets.UTF_8));
  }

  @Test
  void shouldDispatchFileToRealSmtpServer() throws Exception {
    String tenantId = "tenant-email";
    String channelCode = "email-" + UUID.randomUUID();
    Path localFile = Files.createTempFile("dispatch-mail-", ".txt");
    byte[] content = ("mail-payload-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    Files.write(localFile, content);

    DispatchResult result =
        dispatch(
            tenantId,
            Map.ofEntries(
                Map.entry("tenant_id", tenantId),
                Map.entry("channel_type", "EMAIL"),
                Map.entry("channel_code", channelCode),
                Map.entry("smtp_host", "127.0.0.1"),
                Map.entry("smtp_port", ServerSetupTest.SMTP.getPort()),
                Map.entry("smtp_username", SMTP_USER),
                Map.entry("smtp_password", SMTP_PASSWORD),
                Map.entry("smtp_starttls", false),
                Map.entry("mail_from", SMTP_USER),
                Map.entry("mail_to", SMTP_RECIPIENT),
                Map.entry("mail_subject", "Dispatch smoke " + channelCode)),
            fileRecord(localFile.toString(), "LOCAL", null, "attachment.txt", "text/plain"),
            payload("file-email", channelCode, "req-email", "rc-email"));

    assertThat(result.success()).isTrue();
    assertThat(result.evidenceRef()).isEqualTo("mailto:" + SMTP_RECIPIENT);

    Message[] received = waitForMessages(1);
    assertThat(received).hasSize(1);
    Message message = received[0];
    assertThat(message.getSubject()).startsWith("Dispatch smoke ");
    assertThat(message.getFrom()[0].toString()).contains(SMTP_USER);
    assertThat(message.getAllRecipients()[0].toString()).contains(SMTP_RECIPIENT);

    MimeMultipart multipart = (MimeMultipart) message.getContent();
    assertThat(multipart.getCount()).isEqualTo(2);
    assertThat(multipart.getBodyPart(1).getFileName()).isEqualTo("attachment.txt");
    try (InputStream in = multipart.getBodyPart(1).getInputStream()) {
      assertThat(in.readAllBytes()).isEqualTo(content);
    }
  }

  private MinioClient minioClient() {
    return MinioClient.builder()
        .endpoint(minioEndpoint())
        .credentials("minioadmin", "minioadmin123")
        .build();
  }

  private static Message[] waitForMessages(int expectedCount) throws InterruptedException {
    long deadline = System.currentTimeMillis() + MAIL_WAIT_TIMEOUT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      Message[] messages = GREEN_MAIL.getReceivedMessages();
      if (messages.length >= expectedCount) {
        return messages;
      }
      Thread.sleep(200L);
    }
    return GREEN_MAIL.getReceivedMessages();
  }

  private static DispatchPayload payload(
      String fileId, String channelCode, String requestId, String receiptCode) {
    return new DispatchPayload(
        fileId,
        fileId,
        channelCode,
        channelCode,
        requestId,
        receiptCode,
        true,
        false,
        "SYNC",
        Map.of("source", "integration-test"));
  }

  private static Map<String, Object> fileRecord(
      String storagePath, String storageType, String bucket, String originalName, String mimeType) {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("storage_type", storageType);
    record.put("storage_path", storagePath);
    record.put("original_file_name", originalName);
    record.put("file_name", originalName);
    record.put("mime_type", mimeType);
    if (bucket != null) {
      record.put("storage_bucket", bucket);
    }
    return record;
  }

  private DispatchResult dispatch(
      String tenantId,
      Map<String, Object> channelConfig,
      Map<String, Object> fileRecord,
      DispatchPayload payload) {
    return gateway.dispatch(
        new DispatchCommand(
            tenantId, "trace-" + UUID.randomUUID(), fileRecord, channelConfig, payload));
  }
}
