package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteFilesystemNasPathTest {

  @TempDir Path tempDir;

  @Test
  void probeNas_validWritableDir_returnsSuccess() {
    Map<String, Object> config = Map.of("nas_remote_directory", tempDir.toString());
    DispatchChannelProbeResult result = RemoteFilesystemDispatchSupport.probeNas(config);
    assertThat(result.success()).isTrue();
    assertThat(result.message()).contains("probe ok");
  }

  @Test
  void probeNas_missingDirectory_returnsFailure() {
    Map<String, Object> config = Map.of();
    DispatchChannelProbeResult result = RemoteFilesystemDispatchSupport.probeNas(config);
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("missing");
  }

  @Test
  void probeNas_nonExistentDir_createsAndSucceeds() {
    Path newDir = tempDir.resolve("newsubdir");
    Map<String, Object> config = Map.of("nas_remote_directory", newDir.toString());
    DispatchChannelProbeResult result = RemoteFilesystemDispatchSupport.probeNas(config);
    assertThat(result.success()).isTrue();
  }

  @Test
  void probeNas_usesTargetEndpointAsFallback() {
    Map<String, Object> config = Map.of("target_endpoint", tempDir.toString());
    DispatchChannelProbeResult result = RemoteFilesystemDispatchSupport.probeNas(config);
    assertThat(result.success()).isTrue();
  }

  @Test
  void dispatchNas_writesSidecarManifestByDefault() throws Exception {
    byte[] payload = "hello dispatch\n".getBytes(StandardCharsets.UTF_8);
    DispatchFileContentResolver resolver = mock(DispatchFileContentResolver.class);
    Map<String, Object> fileRecord =
        Map.of("id", 10L, "file_name", "source.dat", "biz_date", "2026-06-07");
    when(resolver.openInputStream(fileRecord)).thenReturn(new ByteArrayInputStream(payload));
    DispatchCommand command =
        new DispatchCommand(
            "t1",
            "tr-1",
            fileRecord,
            Map.of("nas_remote_directory", tempDir.toString(), "nas_remote_file_name", "out.dat"),
            new DispatchPayload(
                "10", null, "NAS_CH", null, "ext-1", "R-1", null, null, null, null));

    DispatchResult result = RemoteFilesystemDispatchSupport.dispatchNas(command, resolver);

    Path target = tempDir.resolve("out.dat");
    Path manifest = tempDir.resolve("out.dat.chk");
    assertThat(result.success()).isTrue();
    assertThat(target).hasContent("hello dispatch\n");
    assertThat(manifest).exists();
    assertThat(result.manifestRef()).isNotNull();
    assertThat(result.manifestRef().ref()).isEqualTo(manifest.toRealPath().toString());
    assertThat(result.manifestRef().checksum()).isNotBlank();
    assertThat(result.manifestRef().sizeBytes()).isGreaterThan(0);
    Map<String, Object> manifestJson =
        JsonUtils.fromJson(
            Files.readString(manifest, StandardCharsets.UTF_8),
            new TypeReference<Map<String, Object>>() {});
    assertThat(manifestJson.get("checksumType")).isEqualTo("SHA-256");
    assertThat(manifestJson.get("sizeBytes")).isEqualTo(payload.length);
    assertThat(manifestJson.get("checksumValue")).isEqualTo(sha256(payload));
  }

  @Test
  void dispatchNas_respectsManifestDisabledFlag() throws Exception {
    DispatchFileContentResolver resolver = mock(DispatchFileContentResolver.class);
    Map<String, Object> fileRecord = Map.of("id", 11L, "file_name", "source.dat");
    when(resolver.openInputStream(fileRecord))
        .thenReturn(new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8)));
    DispatchCommand command =
        new DispatchCommand(
            "t1",
            "tr-2",
            fileRecord,
            Map.of(
                "nas_remote_directory",
                tempDir.toString(),
                "nas_remote_file_name",
                "disabled.dat",
                "dispatch_manifest_enabled",
                "false"),
            new DispatchPayload(
                "11", null, "NAS_CH", null, "ext-2", "R-2", null, null, null, null));

    DispatchResult result = RemoteFilesystemDispatchSupport.dispatchNas(command, resolver);

    assertThat(result.success()).isTrue();
    assertThat(result.manifestRef()).isNull();
    assertThat(tempDir.resolve("disabled.dat.chk")).doesNotExist();
  }

  private static String sha256(byte[] payload) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
  }
}
