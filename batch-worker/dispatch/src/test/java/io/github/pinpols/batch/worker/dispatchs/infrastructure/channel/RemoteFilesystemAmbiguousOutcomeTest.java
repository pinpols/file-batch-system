package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.storage.ObjectStoreAccessException;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchRuntimeProperties;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("远程文件系统投递:外部发布后的失败必须保留未知结果,发布前失败可重试")
class RemoteFilesystemAmbiguousOutcomeTest {

  @TempDir
  Path tempDir;

  private final NasCopyExecutor copyExecutor = new NasCopyExecutor();

  @AfterEach
  void shutdownExecutor() {
    copyExecutor.shutdown();
  }

  @Test
  @DisplayName("NAS 主文件已发布但旁挂清单发布失败时返回外部结果未知")
  void shouldReportUnknown_whenNasFileWasPublishedButManifestFailed() throws Exception {
    String fileName = "published.dat";
    Path manifestPath = Files.createDirectory(tempDir.resolve(fileName + ".chk"));
    Files.writeString(manifestPath.resolve("occupied"), "keep", StandardCharsets.UTF_8);
    Path publishedFile = tempDir.resolve(fileName);
    Map<String, Object> fileRecord = fileRecord();
    DispatchFileContentResolver resolver = mock(DispatchFileContentResolver.class);
    when(resolver.openInputStream(fileRecord))
        .thenReturn(new ByteArrayInputStream("published-payload".getBytes(StandardCharsets.UTF_8)));

    DispatchResult result = RemoteFilesystemDispatchSupport.dispatchNas(
        command(
            fileRecord,
            Map.of("nas_remote_directory", tempDir.toString(), "nas_remote_file_name", fileName)),
        resolver,
        new DispatchRuntimeProperties(),
        copyExecutor);

    assertThat(result.success()).isFalse();
    assertThat(result.externalOutcomeUnknown()).isTrue();
    assertThat(result.message()).contains("EXTERNAL_OUTCOME_UNKNOWN");
    assertThat(publishedFile).hasContent("published-payload");
    assertThat(manifestPath.resolve("occupied")).hasContent("keep");
  }

  @Test
  @DisplayName("NAS 文件发布前读取源文件失败时仍返回可重试失败")
  void shouldRemainRetryable_whenNasFailsBeforePublishing() throws Exception {
    Map<String, Object> fileRecord = fileRecord();
    DispatchFileContentResolver resolver = mock(DispatchFileContentResolver.class);
    when(resolver.openInputStream(fileRecord)).thenThrow(new IOException("source unavailable"));

    DispatchResult result = RemoteFilesystemDispatchSupport.dispatchNas(
        command(
            fileRecord,
            Map.of(
                "nas_remote_directory",
                tempDir.toString(),
                "nas_remote_file_name",
                "not-published.dat")),
        resolver,
        new DispatchRuntimeProperties(),
        copyExecutor);

    assertThat(result.success()).isFalse();
    assertThat(result.externalOutcomeUnknown()).isFalse();
    assertThat(tempDir.resolve("not-published.dat")).doesNotExist();
  }

  @Test
  @DisplayName("OSS 主对象已写入但旁挂清单写入异常时返回外部结果未知")
  void shouldReportUnknown_whenOssObjectWasWrittenButManifestFailed() throws Exception {
    byte[] payload = "oss-payload".getBytes(StandardCharsets.UTF_8);
    Map<String, Object> fileRecord = fileRecord();
    DispatchFileContentResolver resolver = mock(DispatchFileContentResolver.class);
    when(resolver.openInputStream(fileRecord)).thenReturn(new ByteArrayInputStream(payload));
    BatchObjectStore objectStore = mock(BatchObjectStore.class);
    AtomicInteger writes = new AtomicInteger();
    doAnswer(invocation -> {
          if (writes.incrementAndGet() == 2) {
            throw new IllegalStateException("password=manifest-secret-marker");
          }
          return null;
        })
        .when(objectStore)
        .put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());

    DispatchResult result = RemoteFilesystemDispatchSupport.dispatchOss(
        command(
            fileRecord,
            Map.of(
                "oss_bucket", "exports",
                "oss_object_name", "published.dat",
                "dispatch_manifest_enabled", true)),
        resolver,
        new S3StorageProperties(),
        objectStore,
        new DispatchRuntimeProperties());

    assertThat(result.success()).isFalse();
    assertThat(result.externalOutcomeUnknown()).isTrue();
    assertThat(result.message()).contains("EXTERNAL_OUTCOME_UNKNOWN");
    assertThat(result.message()).doesNotContain("manifest-secret-marker");
    assertThat(writes).hasValue(2);
    verify(objectStore, times(2))
        .put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
  }

  @Test
  @DisplayName("OSS 首次写入被明确拒绝时仍返回可重试失败")
  void shouldRemainRetryable_whenOssWriteIsRejectedBeforePublication() throws Exception {
    Map<String, Object> fileRecord = fileRecord();
    DispatchFileContentResolver resolver = mock(DispatchFileContentResolver.class);
    when(resolver.openInputStream(fileRecord))
        .thenReturn(new ByteArrayInputStream("oss-payload".getBytes(StandardCharsets.UTF_8)));
    BatchObjectStore objectStore = mock(BatchObjectStore.class);
    doThrow(new ObjectStoreAccessException("password=prepublication-secret-marker"))
        .when(objectStore)
        .put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());

    DispatchResult result = RemoteFilesystemDispatchSupport.dispatchOss(
        command(fileRecord, Map.of("oss_bucket", "exports", "oss_object_name", "rejected.dat")),
        resolver,
        new S3StorageProperties(),
        objectStore,
        new DispatchRuntimeProperties());

    assertThat(result.success()).isFalse();
    assertThat(result.externalOutcomeUnknown()).isFalse();
    assertThat(result.message()).doesNotContain("prepublication-secret-marker");
  }

  private static DispatchCommand command(
      Map<String, Object> fileRecord, Map<String, Object> channelConfig) {
    DispatchPayload payload = new DispatchPayload(
        "10", null, "CHANNEL", null, "request-1", "receipt-1", null, null, null, null);
    return new DispatchCommand("tenant-1", "trace-1", fileRecord, channelConfig, payload);
  }

  private static Map<String, Object> fileRecord() {
    return Map.of("id", 10L, "file_name", "source.dat", "mime_type", "application/octet-stream");
  }
}
