package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.security.DnsResolveGuard;
import io.github.pinpols.batch.testing.TestContainerLabels;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

@DisplayName("SFTP 解析地址后的严格主机密钥验证")
class SftpStrictHostKeyIntegrationTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  @DisplayName("非默认端口连接使用原始主机名匹配公钥，错误公钥必须拒绝")
  void shouldVerifyOriginalHostnameAndRejectWrongKey() throws Exception {
    try (GenericContainer<?> server = new GenericContainer<>(
            DockerImageName.parse("jmcombs/sftp:alpine"))
        .withLabel(TestContainerLabels.OWNER, TestContainerLabels.OWNER_VALUE)
        .withExposedPorts(22)
        .withCommand("batch:test-pass:::upload")
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)))) {
      server.start();
      String originalHost = "sftp.example.test";
      int port = server.getMappedPort(22);
      String knownHost = "[" + originalHost + "]:" + port;
      var keyResult = server.execInContainer("sh", "-c", "cat /etc/ssh/ssh_host_*_key.pub");
      assertThat(keyResult.getExitCode()).isZero();
      String knownKeys = keyResult
          .getStdout()
          .lines()
          .filter(line -> !line.isBlank())
          .map(line -> knownHost + " " + line)
          .collect(Collectors.joining("\n", "", "\n"));
      Path correctKeys = temporaryDirectory.resolve("known-hosts");
      Files.writeString(correctKeys, knownKeys);
      KeyPair wrongKey = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048);
      Path wrongKeys = temporaryDirectory.resolve("wrong-known-hosts");
      try {
        Files.writeString(
            wrongKeys,
            knownHost + " ssh-rsa "
                + Base64.getEncoder().encodeToString(wrongKey.getPublicKeyBlob()) + "\n");
      } finally {
        wrongKey.dispose();
      }
      DispatchFileContentResolver resolver = mock(DispatchFileContentResolver.class);
      when(resolver.openInputStream(anyMap()))
          .thenReturn(
              new ByteArrayInputStream("first".getBytes(StandardCharsets.UTF_8)),
              new ByteArrayInputStream("replacement".getBytes(StandardCharsets.UTF_8)));
      MockEnvironment environment = new MockEnvironment();
      environment.setActiveProfiles("prod");
      SftpDispatchChannelAdapter adapter =
          new SftpDispatchChannelAdapter(resolver, new BatchSecurityProperties(), environment);
      InetAddress address = InetAddress.getByName(server.getHost());
      try (MockedStatic<DnsResolveGuard> dns = mockStatic(DnsResolveGuard.class)) {
        dns.when(() -> DnsResolveGuard.resolveAndValidate(originalHost)).thenReturn(address);
        DispatchResult accepted = adapter.dispatch(command(originalHost, port, correctKeys));
        assertThat(accepted.success()).as(accepted.message()).isTrue();
        DispatchResult replaced = adapter.dispatch(command(originalHost, port, correctKeys));
        assertThat(replaced.success()).as(replaced.message()).isTrue();
        var published = server.execInContainer("cat", "/home/batch/upload/test.txt");
        assertThat(published.getExitCode()).isZero();
        assertThat(published.getStdout()).isEqualTo("replacement");
        DispatchResult rejected = adapter.dispatch(command(originalHost, port, wrongKeys));
        assertThat(rejected.success()).isFalse();
        var afterRejection = server.execInContainer("cat", "/home/batch/upload/test.txt");
        assertThat(afterRejection.getExitCode()).isZero();
        assertThat(afterRejection.getStdout()).isEqualTo("replacement");
      } finally {
        adapter.shutdownDisconnectExecutor();
      }
    }
  }

  private static DispatchCommand command(String host, int port, Path keys) {
    Map<String, Object> config = Map.of(
        "sftp_host",
        host,
        "sftp_port",
        port,
        "sftp_user",
        "batch",
        "sftp_password",
        "test-pass",
        "sftp_remote_directory",
        "/upload/",
        "sftp_known_hosts_path",
        keys.toString());
    DispatchPayload payload = DispatchPayload.builder()
        .externalRequestId("request")
        .receiptCode("receipt")
        .build();
    return new DispatchCommand("tenant", "trace", Map.of("file_name", "test.txt"), config, payload);
  }
}
