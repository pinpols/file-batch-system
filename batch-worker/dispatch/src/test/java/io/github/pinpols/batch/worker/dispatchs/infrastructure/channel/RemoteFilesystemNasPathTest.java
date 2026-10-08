package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchRuntimeProperties;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("远端文件系统渠道:目录探测的成功与失败判定,以及投递落盘、旁挂清单开关与线程池停机后的行为")
class RemoteFilesystemNasPathTest {

  @TempDir
  Path tempDir;

  private final DispatchRuntimeProperties runtimeProperties = new DispatchRuntimeProperties();
  private final NasCopyExecutor copyExecutor = new NasCopyExecutor();

  @AfterEach
  void shutdownExecutor() {
    copyExecutor.shutdown();
  }

  @Test
  @DisplayName("远端目录存在且可写时探测成功,并回执探测通过")
  void probeNas_validWritableDir_returnsSuccess() {
    Map<String, Object> config = Map.of("nas_remote_directory", tempDir.toString());
    DispatchChannelProbeResult result = RemoteFilesystemDispatchSupport.probeNas(config);
    assertThat(result.success()).isTrue();
    assertThat(result.message()).contains("probe ok");
  }

  @Test
  @DisplayName("渠道配置未给出远端目录时探测失败,并回执目录缺失")
  void probeNas_missingDirectory_returnsFailure() {
    Map<String, Object> config = Map.of();
    DispatchChannelProbeResult result = RemoteFilesystemDispatchSupport.probeNas(config);
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("missing");
  }

  @Test
  @DisplayName("远端目录尚不存在时自动创建,创建后探测成功")
  void probeNas_nonExistentDir_createsAndSucceeds() {
    Path newDir = tempDir.resolve("newsubdir");
    Map<String, Object> config = Map.of("nas_remote_directory", newDir.toString());
    DispatchChannelProbeResult result = RemoteFilesystemDispatchSupport.probeNas(config);
    assertThat(result.success()).isTrue();
  }

  @Test
  @DisplayName("NAS 目录经符号链接解析时探测仍成功并经过告警分支")
  void probeNas_symlinkDirectory_logsWarningAndSucceeds() throws Exception {
    Path realDirectory = Files.createDirectory(tempDir.resolve("nas-real"));
    Path symlinkDirectory = tempDir.resolve("nas-link");
    try {
      Files.createSymbolicLink(symlinkDirectory, realDirectory);
    } catch (IOException | UnsupportedOperationException | SecurityException ex) {
      assumeTrue(false, "当前文件系统不支持创建符号链接: " + ex.getClass().getSimpleName());
      return;
    }

    Map<String, Object> config = Map.of("nas_remote_directory", symlinkDirectory.toString());
    DispatchChannelProbeResult result = RemoteFilesystemDispatchSupport.probeNas(config);

    assertThat(result.success()).isTrue();
  }

  @Test
  @DisplayName("未配置远端目录时回退到目标端点目录探测,探测成功")
  void probeNas_usesTargetEndpointAsFallback() {
    Map<String, Object> config = Map.of("target_endpoint", tempDir.toString());
    DispatchChannelProbeResult result = RemoteFilesystemDispatchSupport.probeNas(config);
    assertThat(result.success()).isTrue();
  }

  @Test
  @DisplayName("默认写出旁挂清单,清单带 SHA-256 校验和与字节数,目标文件内容与源文件一致")
  void dispatchNas_writesSidecarManifestByDefault() throws Exception {
    byte[] payload = "hello dispatch\n".getBytes(StandardCharsets.UTF_8);
    DispatchFileContentResolver resolver = mock(DispatchFileContentResolver.class);
    Map<String, Object> fileRecord =
        Map.of("id", 10L, "file_name", "source.dat", "biz_date", "2026-06-07");
    when(resolver.openInputStream(fileRecord)).thenReturn(new ByteArrayInputStream(payload));
    DispatchCommand command = new DispatchCommand(
        "t1",
        "tr-1",
        fileRecord,
        Map.of("nas_remote_directory", tempDir.toString(), "nas_remote_file_name", "out.dat"),
        new DispatchPayload("10", null, "NAS_CH", null, "ext-1", "R-1", null, null, null, null));

    DispatchResult result = RemoteFilesystemDispatchSupport.dispatchNas(
        command, resolver, runtimeProperties, copyExecutor);

    Path target = tempDir.resolve("out.dat");
    Path manifest = tempDir.resolve("out.dat.chk");
    assertThat(result.success()).isTrue();
    assertThat(target).hasContent("hello dispatch\n");
    assertThat(manifest).exists();
    assertThat(result.manifestRef()).isNotNull();
    assertThat(result.manifestRef().ref()).isEqualTo(manifest.toRealPath().toString());
    assertThat(result.manifestRef().checksum()).isNotBlank();
    assertThat(result.manifestRef().sizeBytes()).isGreaterThan(0);
    Map<String, Object> manifestJson = JsonUtils.fromJson(
        Files.readString(manifest, StandardCharsets.UTF_8),
        new TypeReference<Map<String, Object>>() {});
    assertThat(manifestJson).containsEntry("checksumType", "SHA-256");
    assertThat(manifestJson).containsEntry("sizeBytes", payload.length);
    assertThat(manifestJson).containsEntry(PipelineRuntimeKeys.CHECKSUM_VALUE, sha256(payload));
  }

  @Test
  @DisplayName("旁挂清单开关关闭时投递仍成功,但不生成清单引用与清单文件")
  void dispatchNas_respectsManifestDisabledFlag() throws Exception {
    DispatchFileContentResolver resolver = mock(DispatchFileContentResolver.class);
    Map<String, Object> fileRecord = Map.of("id", 11L, "file_name", "source.dat");
    when(resolver.openInputStream(fileRecord))
        .thenReturn(new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8)));
    DispatchCommand command = new DispatchCommand(
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
        new DispatchPayload("11", null, "NAS_CH", null, "ext-2", "R-2", null, null, null, null));

    DispatchResult result = RemoteFilesystemDispatchSupport.dispatchNas(
        command, resolver, runtimeProperties, copyExecutor);

    assertThat(result.success()).isTrue();
    assertThat(result.manifestRef()).isNull();
    assertThat(tempDir.resolve("disabled.dat.chk")).doesNotExist();
  }

  @Test
  @DisplayName("拷贝线程池已停机时投递失败并回执停机中,不会把线程池重新拉起")
  void dispatchNas_doesNotResurrectExecutorAfterShutdown() throws Exception {
    copyExecutor.shutdown();
    byte[] payload = "restartable executor\n".getBytes(StandardCharsets.UTF_8);
    DispatchFileContentResolver resolver = mock(DispatchFileContentResolver.class);
    Map<String, Object> fileRecord = Map.of("id", 12L, "file_name", "source.dat");
    when(resolver.openInputStream(fileRecord)).thenReturn(new ByteArrayInputStream(payload));
    DispatchCommand command = new DispatchCommand(
        "t1",
        "tr-3",
        fileRecord,
        Map.of(
            "nas_remote_directory",
            tempDir.toString(),
            "nas_remote_file_name",
            "after-shutdown.dat"),
        new DispatchPayload("12", null, "NAS_CH", null, "ext-3", "R-3", null, null, null, null));

    DispatchResult result = RemoteFilesystemDispatchSupport.dispatchNas(
        command, resolver, runtimeProperties, copyExecutor);

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("stopping");
  }

  private static String sha256(byte[] payload) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
  }
}
