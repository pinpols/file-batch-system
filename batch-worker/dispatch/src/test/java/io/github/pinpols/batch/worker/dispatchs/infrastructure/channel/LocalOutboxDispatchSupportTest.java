package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.worker.dispatchs.config.DispatchRuntimeProperties;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalOutboxDispatchSupportTest {

  @Test
  void shouldWriteEnvelopeAndSidecarManifestInsideSandbox(@TempDir Path tempDir) throws Exception {
    Path sandbox = tempDir.resolve("sandbox");
    Path target = sandbox.resolve("outbox");
    Files.createDirectories(sandbox);
    DispatchRuntimeProperties properties = runtimeProperties(sandbox);

    DispatchResult result = LocalOutboxDispatchSupport.writeFilesystemEnvelope(
        command(target), false, null, properties);

    assertThat(result.success()).isTrue();
    assertThat(result.manifestRef()).isNotNull();
    assertThat(result.manifestRef().ref()).endsWith(".json.chk");
    assertThat(result.manifestRef().checksum()).isNotBlank();
    assertThat(result.manifestRef().sizeBytes()).isPositive();
    try (Stream<Path> files = Files.list(target)) {
      assertThat(files).anyMatch(path -> path.getFileName().toString().endsWith(".json"));
    }
    try (Stream<Path> files = Files.list(target)) {
      assertThat(files).anyMatch(path -> path.getFileName().toString().endsWith(".json.chk"));
    }
  }

  @Test
  void shouldRejectTargetEndpointEscapingSandbox(@TempDir Path tempDir) throws Exception {
    Path sandbox = tempDir.resolve("sandbox");
    Path outside = tempDir.resolve("outside");
    Files.createDirectories(sandbox);
    Files.createDirectories(outside);
    DispatchRuntimeProperties properties = runtimeProperties(sandbox);

    DispatchResult result = LocalOutboxDispatchSupport.writeFilesystemEnvelope(
        command(outside), false, null, properties);

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("escapes sandbox root");
  }

  private static DispatchRuntimeProperties runtimeProperties(Path sandbox) {
    DispatchRuntimeProperties properties = new DispatchRuntimeProperties();
    properties.setLocalSandboxRoot(sandbox.toString());
    return properties;
  }

  private static DispatchCommand command(Path target) {
    DispatchPayload payload = new DispatchPayload(
        "file-1",
        "FILE_A",
        "LOCAL_CH",
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
        Map.of(
            "channel_type", "LOCAL",
            "channel_code", "../local channel",
            "target_endpoint", target.toString()),
        payload);
  }
}
