package com.example.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
}
