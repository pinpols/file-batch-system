package com.example.batch.worker.exports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.infrastructure.S3ExportStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

class StoreStepTest {

  @Test
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
  void execute_returnsInvalid_whenGeneratedFileMissingOnDisk() {
    S3ExportStorage storage = mock(S3ExportStorage.class);
    BatchObjectCryptoService crypto = mock(BatchObjectCryptoService.class);
    StoreStep step = new StoreStep(storage, crypto);

    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.getAttributes().put("generatedFilePath", "/tmp/not-exist-" + System.nanoTime() + ".json");

    var result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_STORE_INVALID");
    verify(storage, never()).writeObject(anyString(), any(Path.class), anyString());
  }

  @Test
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
    when(storage.sha256Hex(eq("tmp.part"))).thenReturn(expectedSha);
    when(storage.sha256Hex(anyString())).thenReturn(expectedSha);

    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.getAttributes().put("generatedFilePath", generated.toString());
    ctx.getAttributes().put("exportFileFormatType", "JSON");

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes().get("exportStoreCommitted")).isEqualTo(Boolean.TRUE);
    assertThat(Files.exists(generated)).isFalse();
    verify(storage).copyObject(eq("tmp.part"), anyString());
    verify(storage).removeObject(eq("tmp.part"));
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
