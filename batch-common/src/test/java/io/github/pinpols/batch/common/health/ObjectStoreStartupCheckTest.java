package io.github.pinpols.batch.common.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.storage.FilesystemObjectStore;
import io.github.pinpols.batch.common.storage.ObjectListing;
import io.github.pinpols.batch.common.storage.ObjectSummary;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ObjectStoreStartupCheck} 单测:探针成功跑通 + 各失败点 fail-fast + 失败也清理探针。 */
@DisplayName("对象存储启动自检:探针读写校验通过,以及桶名、内容、长度、预签名、上传与清理各失败点的快速失败")
class ObjectStoreStartupCheckTest {

  private static final String BUCKET = "probe-bucket";
  private static final String DOWNLOAD_BASE_URL = "https://example.invalid/files";
  private static final String SECRET = "startup-check-secret-1234567890";
  // 探针 payload = "batch-startup-probe-"(20) + UUID(36) = 56 字节,长度确定。
  private static final long PROBE_PAYLOAD_LEN = 56L;

  private FilesystemObjectStore realStore(Path root) {
    return new FilesystemObjectStore(root.toString(), DOWNLOAD_BASE_URL, SECRET);
  }

  @Test
  @DisplayName("可用存储上探针读写校验通过,且探针对象被清理")
  void shouldPassAgainstWorkingStore(@TempDir Path root) {
    ObjectStoreStartupCheck check = new ObjectStoreStartupCheck(realStore(root), BUCKET);

    check.run(null);
    // 跑通即不抛;探针应被清理,bucket 目录下不残留 __batch-startup-probe__ 对象。
    FilesystemObjectStore store = realStore(root);
    assertThat(store.list(BUCKET, "__batch-startup-probe__/", null, 100).objects())
        .isEmpty();
  }

  @Test
  @DisplayName("桶名为空白时在自检开始前快速失败")
  void shouldFailFastWhenBucketBlank(@TempDir Path root) {
    ObjectStoreStartupCheck check = new ObjectStoreStartupCheck(realStore(root), "  ");

    assertThatThrownBy(() -> check.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bucket");
  }

  @Test
  @DisplayName("回读内容与写入不一致时快速失败并清理探针")
  void shouldFailFastWhenGetReturnsWrongContent() {
    BatchObjectStore store = mock(BatchObjectStore.class);
    when(store.exists(anyString(), anyString())).thenReturn(true);
    when(store.statSize(anyString(), anyString())).thenReturn(PROBE_PAYLOAD_LEN);
    when(store.get(anyString(), anyString())).thenReturn(InputStream.nullInputStream());

    ObjectStoreStartupCheck check = new ObjectStoreStartupCheck(store, BUCKET);

    assertThatThrownBy(() -> check.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("get");
    // 失败路径也要清理探针。
    verify(store).deleteMany(anyString(), any());
  }

  @Test
  @DisplayName("对象长度小于探针写入长度时快速失败")
  void shouldFailFastWhenStatSizeMismatch() {
    BatchObjectStore store = mock(BatchObjectStore.class);
    when(store.exists(anyString(), anyString())).thenReturn(true);
    when(store.statSize(anyString(), anyString())).thenReturn(1L);

    ObjectStoreStartupCheck check = new ObjectStoreStartupCheck(store, BUCKET);

    assertThatThrownBy(() -> check.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("statSize");
    verify(store).deleteMany(anyString(), any());
  }

  @Test
  @DisplayName("预签名地址为空白时快速失败")
  void shouldFailFastWhenPresignReturnsBlank() {
    BatchObjectStore store = mock(BatchObjectStore.class);
    AtomicReference<String> probeKey = new AtomicReference<>();
    AtomicReference<byte[]> probePayload = new AtomicReference<>();
    doAnswer(invocation -> {
          probeKey.set(invocation.getArgument(1));
          try (InputStream in = invocation.getArgument(2)) {
            probePayload.set(in.readAllBytes());
          }
          return null;
        })
        .when(store)
        .put(anyString(), anyString(), any(), anyLong(), anyString());
    when(store.exists(anyString(), anyString())).thenReturn(true);
    when(store.statSize(anyString(), anyString())).thenReturn(PROBE_PAYLOAD_LEN);
    when(store.get(anyString(), anyString()))
        .thenAnswer(invocation -> new ByteArrayInputStream(probePayload.get()));
    when(store.list(anyString(), anyString(), any(), anyInt()))
        .thenAnswer(invocation -> new ObjectListing(
            List.of(new ObjectSummary(probeKey.get(), PROBE_PAYLOAD_LEN, Instant.now(), "e")),
            null));
    when(store.presign(anyString(), anyString(), any())).thenReturn(" ");

    ObjectStoreStartupCheck check = new ObjectStoreStartupCheck(store, BUCKET);

    assertThatThrownBy(() -> check.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("presign");
  }

  @Test
  @DisplayName("对象长度大于明文长度时不误判失败")
  void shouldPassWhenStatSizeExceedsPayload(@TempDir Path root) {
    // 回归:加密装饰层 statSize 返回密文字节数(> 明文),探针不得据此误判失败。
    // 用真实 FS store + 只放大 statSize 的薄委托模拟「密文比明文长」。
    BatchObjectStore inflated = new InflatedStatStore(realStore(root), 64);
    ObjectStoreStartupCheck check = new ObjectStoreStartupCheck(inflated, BUCKET);

    // 内容仍由真实 get 全等校验;statSize 偏大不应误判 → 不抛即通过。
    check.run(null);
    assertThat(
            realStore(root).list(BUCKET, "__batch-startup-probe__/", null, 100).objects())
        .isEmpty();
  }

  @Test
  @DisplayName("上传探针抛出异常时快速失败")
  void shouldFailFastWhenPutThrows() {
    BatchObjectStore store = mock(BatchObjectStore.class);
    doThrow(new RuntimeException("connection refused"))
        .when(store)
        .put(anyString(), anyString(), any(), anyLong(), anyString());

    ObjectStoreStartupCheck check = new ObjectStoreStartupCheck(store, BUCKET);

    assertThatThrownBy(() -> check.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("path-style");
  }

  @Test
  @DisplayName("清理探针失败时不掩盖自检失败原因")
  void shouldNotThrowWhenCleanupFails() {
    BatchObjectStore store = mock(BatchObjectStore.class);
    when(store.exists(anyString(), anyString())).thenReturn(true);
    when(store.statSize(anyString(), anyString())).thenReturn(1L);
    doThrow(new RuntimeException("cleanup boom")).when(store).deleteMany(anyString(), any());

    ObjectStoreStartupCheck check = new ObjectStoreStartupCheck(store, BUCKET);

    // 探针自检失败抛 ISE,但清理失败被吞(不掩盖原因)。
    assertThatThrownBy(() -> check.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("statSize");
    verify(store, never()).get(anyString(), anyString());
  }

  /** 薄委托:除 statSize 在真实值上加固定开销(模拟密文 magic+header+GCM tag)外,全部透传。 */
  private static final class InflatedStatStore implements BatchObjectStore {
    private final BatchObjectStore delegate;
    private final long overhead;

    InflatedStatStore(BatchObjectStore delegate, long overhead) {
      this.delegate = delegate;
      this.overhead = overhead;
    }

    @Override
    public void put(String bucket, String key, InputStream in, long size, String contentType) {
      delegate.put(bucket, key, in, size, contentType);
    }

    @Override
    public void copy(String bucket, String srcKey, String dstKey) {
      delegate.copy(bucket, srcKey, dstKey);
    }

    @Override
    public void delete(String bucket, String key) {
      delegate.delete(bucket, key);
    }

    @Override
    public InputStream get(String bucket, String key) {
      return delegate.get(bucket, key);
    }

    @Override
    public InputStream getFrom(String bucket, String key, long offset) {
      return delegate.getFrom(bucket, key, offset);
    }

    @Override
    public long statSize(String bucket, String key) {
      return delegate.statSize(bucket, key) + overhead;
    }

    @Override
    public boolean exists(String bucket, String key) {
      return delegate.exists(bucket, key);
    }

    @Override
    public ObjectListing list(String bucket, String prefix, String afterMarker, int maxKeys) {
      return delegate.list(bucket, prefix, afterMarker, maxKeys);
    }

    @Override
    public String presign(String bucket, String key, Duration ttl) {
      return delegate.presign(bucket, key, ttl);
    }
  }
}
