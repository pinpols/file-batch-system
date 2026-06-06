package com.example.batch.console.domain.file.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.config.FilesystemStorageProperties;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.storage.BatchObjectStore;
import com.example.batch.common.storage.FilesystemPresignTokens;
import com.example.batch.common.storage.ObjectListing;
import com.example.batch.common.storage.ObjectNotFoundException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** {@link ConsoleFilesystemPresignDownloadController} 单测：HMAC 校验通过 / 篡改 / 过期 / traversal。 */
class FilesystemPresignDownloadControllerTest {

  private static final String BUCKET = "test-bucket";
  private static final String KEY = "dir/a.bin";
  private static final String SECRET = "test-presign-secret-1234567890";

  private ConsoleFilesystemPresignDownloadController controller;
  private InMemoryObjectStore stubStore;

  @BeforeEach
  void setUp() {
    FilesystemStorageProperties props = new FilesystemStorageProperties();
    props.setPresignSecret(SECRET);
    props.setDownloadBaseUrl("https://example.invalid/api/console/files/fs-download");
    BatchSecurityProperties security = new BatchSecurityProperties();
    stubStore = new InMemoryObjectStore();
    stubStore.put(
        BUCKET,
        KEY,
        new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8)),
        "payload".length(),
        "application/octet-stream");
    controller = new ConsoleFilesystemPresignDownloadController(stubStore, props, security);
  }

  @Test
  void shouldStreamPayloadWhenSignatureIsValid() throws Exception {
    long exp = Instant.now().plusSeconds(60).getEpochSecond();
    String sig = FilesystemPresignTokens.sign(BUCKET, KEY, Instant.ofEpochSecond(exp), SECRET);

    ResponseEntity<StreamingResponseBody> resp = controller.download(BUCKET, KEY, exp, sig);

    assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    resp.getBody().writeTo(out);
    assertThat(new String(out.toByteArray(), StandardCharsets.UTF_8)).isEqualTo("payload");
    assertThat(resp.getHeaders().getFirst("Content-Disposition")).contains("a.bin");
  }

  @Test
  void shouldRejectTamperedSignature() {
    long exp = Instant.now().plusSeconds(60).getEpochSecond();
    assertThatThrownBy(() -> controller.download(BUCKET, KEY, exp, "tampered"))
        .isInstanceOf(BizException.class);
  }

  @Test
  void shouldRejectExpiredToken() {
    long exp = Instant.now().minusSeconds(60).getEpochSecond();
    String sig = FilesystemPresignTokens.sign(BUCKET, KEY, Instant.ofEpochSecond(exp), SECRET);
    assertThatThrownBy(() -> controller.download(BUCKET, KEY, exp, sig))
        .isInstanceOf(BizException.class);
  }

  @Test
  void shouldRejectTraversalKey() {
    long exp = Instant.now().plusSeconds(60).getEpochSecond();
    String key = "../etc/passwd";
    String sig = FilesystemPresignTokens.sign(BUCKET, key, Instant.ofEpochSecond(exp), SECRET);
    assertThatThrownBy(() -> controller.download(BUCKET, key, exp, sig))
        .isInstanceOf(BizException.class);
  }

  /** 极简 in-memory store，避免引入 TempDir。 */
  private static final class InMemoryObjectStore implements BatchObjectStore {
    private final Map<String, byte[]> store = new HashMap<>();

    @Override
    public void put(String bucket, String key, InputStream in, long size, String contentType) {
      try {
        store.put(bucket + "|" + key, in.readAllBytes());
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    @Override
    public void copy(String bucket, String src, String dst) {
      store.put(bucket + "|" + dst, store.get(bucket + "|" + src));
    }

    @Override
    public void delete(String bucket, String key) {
      store.remove(bucket + "|" + key);
    }

    @Override
    public InputStream get(String bucket, String key) {
      byte[] data = store.get(bucket + "|" + key);
      if (data == null) {
        throw new ObjectNotFoundException("missing: " + bucket + "/" + key);
      }
      return new ByteArrayInputStream(data);
    }

    @Override
    public InputStream getFrom(String bucket, String key, long offset) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long statSize(String bucket, String key) {
      return store.getOrDefault(bucket + "|" + key, new byte[0]).length;
    }

    @Override
    public boolean exists(String bucket, String key) {
      return store.containsKey(bucket + "|" + key);
    }

    @Override
    public ObjectListing list(String bucket, String prefix, String marker, int max) {
      return new ObjectListing(List.of(), null);
    }

    @Override
    public String presign(String bucket, String key, Duration ttl) {
      return "stub";
    }
  }
}
