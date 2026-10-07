package io.github.pinpols.batch.worker.exports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.infrastructure.S3ExportStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

@DisplayName("导出存储阶段单测:校验和确认,加密上传,失败清理与演练语义")
class StoreStepTest {

  @ParameterizedTest
  @DisplayName("加密或上传失败时删除加密副本,保留原始生成文件")
  @ValueSource(booleans = {true, false})
  void shouldDeleteEncryptedCopy_whenEncryptionOrUploadFails(boolean failEncryption)
      throws Exception {
    S3ExportStorage storage = mock(S3ExportStorage.class);
    BatchObjectCryptoService crypto = mock(BatchObjectCryptoService.class);
    when(crypto.shouldEncrypt(any())).thenReturn(true);
    when(crypto.resolveKeyRef(any())).thenReturn("key");
    AtomicReference<Path> encrypted = new AtomicReference<>();
    when(crypto.encrypt(any(Path.class), any(Path.class), anyString())).thenAnswer(invocation -> {
      Path target = invocation.getArgument(1);
      encrypted.set(target);
      Files.writeString(target, "partial cipher text");
      if (failEncryption) throw new IllegalStateException("encryption failure");
      return target;
    });
    if (!failEncryption) {
      when(storage.writeObject(anyString(), any(Path.class), anyString()))
          .thenThrow(new IllegalStateException("upload failure"));
    }
    Path generated = Files.createTempFile("export-cleanup-test-", ".json");
    try {
      Files.writeString(generated, "{}");
      ExportJobContext context = new ExportJobContext();
      context.getAttributes().put(PipelineRuntimeKeys.GENERATED_FILE_PATH, generated.toString());
      assertThat(new StoreStep(storage, crypto).execute(context).success()).isFalse();
      assertThat(encrypted.get()).doesNotExist();
      assertThat(generated).exists();
    } finally {
      Files.deleteIfExists(generated);
      if (encrypted.get() != null) Files.deleteIfExists(encrypted.get());
    }
  }

  @Test
  @DisplayName("演练模式只计算校验和,不上传不加密也不触碰存储")
  void execute_dryRunComputesChecksumWithoutUploadingOrEncrypting() throws Exception {
    S3ExportStorage storage = mock(S3ExportStorage.class);
    BatchObjectCryptoService crypto = mock(BatchObjectCryptoService.class);
    Path generated = Files.createTempFile("export-dry-run-", ".json");
    Files.writeString(generated, "{\"dryRun\":true}");
    ExportJobContext context = new ExportJobContext();
    context.getAttributes().put(PipelineRuntimeKeys.DRY_RUN, true);
    context.getAttributes().put(PipelineRuntimeKeys.GENERATED_FILE_PATH, generated.toString());

    var result = new StoreStep(storage, crypto).execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes())
        .containsEntry(PipelineRuntimeKeys.OBJECT_NAME, "dry-run/no-upload")
        .containsEntry(PipelineRuntimeKeys.EXPORT_STORE_COMMITTED, Boolean.TRUE)
        .containsEntry("checksumType", "SHA-256");
    verifyNoInteractions(storage, crypto);
    Files.deleteIfExists(generated);
  }

  @Test
  @DisplayName("缺少生成文件路径时返回存储阶段非法,不发起上传")
  void execute_returnsInvalid_whenGeneratedFilePathMissing() {
    S3ExportStorage storage = mock(S3ExportStorage.class);
    BatchObjectCryptoService crypto = mock(BatchObjectCryptoService.class);
    StoreStep step = new StoreStep(storage, crypto);

    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");

    var result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_STORE_INVALID");
    verify(storage, never()).writeObject(anyString(), any(Path.class), anyString());
  }

  @Test
  @DisplayName("生成文件在磁盘上不存在时返回存储阶段非法")
  void execute_returnsInvalid_whenGeneratedFileMissingOnDisk() {
    S3ExportStorage storage = mock(S3ExportStorage.class);
    BatchObjectCryptoService crypto = mock(BatchObjectCryptoService.class);
    StoreStep step = new StoreStep(storage, crypto);

    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.getAttributes()
        .put(
            PipelineRuntimeKeys.GENERATED_FILE_PATH,
            "/tmp/not-exist-" + System.nanoTime() + ".json");

    var result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_STORE_INVALID");
    verify(storage, never()).writeObject(anyString(), any(Path.class), anyString());
  }

  @Test
  @DisplayName("校验和一致时上传并转正对象,随后删除本地生成文件")
  void execute_uploadsAndPromotes_whenDigestMatches_andDeletesLocalFile() throws Exception {
    S3ExportStorage storage = mock(S3ExportStorage.class);
    BatchObjectCryptoService crypto = mock(BatchObjectCryptoService.class);
    when(crypto.shouldEncrypt(any())).thenReturn(false);

    StoreStep step = new StoreStep(storage, crypto);

    Path generated = Files.createTempFile("export-generated-", ".json");
    Files.writeString(generated, "{\"ok\":true}");

    // Match both temp and final sha256 with local file's computed sha
    // The method calls sha256Hex(tempKey) then sha256Hex(objectName).
    when(storage.writeObject(anyString(), eq(generated), anyString())).thenReturn("tmp.part");
    // We don't know expectedSha beforehand; so return whatever StoreStep computed by echoing
    // back via Answer-like behavior isn't available here; instead compute it ourselves:
    String expectedSha = TestSha256.sha256Hex(generated);
    when(storage.sha256Hex("tmp.part")).thenReturn(expectedSha);
    when(storage.sha256Hex(anyString())).thenReturn(expectedSha);

    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.getAttributes().put(PipelineRuntimeKeys.GENERATED_FILE_PATH, generated.toString());
    ctx.getAttributes().put(PipelineRuntimeKeys.EXPORT_FILE_FORMAT_TYPE, "JSON");

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes())
        .containsEntry(PipelineRuntimeKeys.EXPORT_STORE_COMMITTED, Boolean.TRUE);
    assertThat(Files.exists(generated)).isFalse();
    verify(storage).copyObject(eq("tmp.part"), anyString());
    verify(storage).removeObject("tmp.part");
  }

  @Test
  @DisplayName("模板要求加密时先生成密文再上传,上传后清理密文副本")
  void execute_encryptsBeforeUpload_whenTemplateRequiresEncryption() throws Exception {
    S3ExportStorage storage = mock(S3ExportStorage.class);
    BatchObjectCryptoService crypto = mock(BatchObjectCryptoService.class);
    when(crypto.shouldEncrypt(any())).thenReturn(true);
    when(crypto.resolveKeyRef(any())).thenReturn("key-ref");
    doAnswer(invocation -> {
          Files.copy(
              invocation.getArgument(0, Path.class),
              invocation.getArgument(1, Path.class),
              StandardCopyOption.REPLACE_EXISTING);
          return invocation.getArgument(1, Path.class);
        })
        .when(crypto)
        .encrypt(any(Path.class), any(Path.class), anyString());

    Path generated = Files.createTempFile("export-encrypted-", ".json");
    Files.writeString(generated, "{\"secret\":true}");
    String expectedSha = TestSha256.sha256Hex(generated);
    when(storage.writeObject(anyString(), any(Path.class), eq("application/octet-stream")))
        .thenReturn("encrypted.part");
    when(storage.sha256Hex(anyString())).thenReturn(expectedSha);

    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.getAttributes().put(PipelineRuntimeKeys.GENERATED_FILE_PATH, generated.toString());
    ctx.getAttributes().put(PipelineRuntimeKeys.EXPORT_FILE_FORMAT_TYPE, "JSON");
    ctx.getAttributes()
        .put(
            PipelineRuntimeKeys.TEMPLATE_CONFIG,
            Map.of("content_encryption_enabled", true, "encryption_key_ref", "key-ref"));

    var result = new StoreStep(storage, crypto).execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes()).containsEntry("contentEncryptionEnabled", true);
    verify(crypto).encrypt(eq(generated), any(Path.class), eq("key-ref"));
    ArgumentCaptor<Path> uploadedPath = ArgumentCaptor.forClass(Path.class);
    verify(storage)
        .writeObject(anyString(), uploadedPath.capture(), eq("application/octet-stream"));
    assertThat(Files.exists(uploadedPath.getValue())).isFalse();
  }

  /** Local helper to avoid duplicating StoreStep's sha256 calculation logic in tests. */
  private static final class TestSha256 {
    private TestSha256() {}

    static String sha256Hex(Path path) throws Exception {
      MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      try (var inputStream = Files.newInputStream(path)) {
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
          if (read > 0) {
            messageDigest.update(buffer, 0, read);
          }
        }
      }
      byte[] digest = messageDigest.digest();
      StringBuilder builder = new StringBuilder();
      for (byte item : digest) {
        builder.append(String.format("%02x", item));
      }
      return builder.toString();
    }
  }
}
