package io.github.pinpols.batch.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("部署命名空间对象存储:物理路径隔离并保持调用方逻辑 key")
class NamespacedObjectStoreTest {

  @Test
  @DisplayName("S3 类能力和所有对象操作都使用物理 namespace key,列举结果还原逻辑 key")
  void shouldNamespaceDelegateOperationsAndTranslateListing() {
    BatchObjectStore delegate = mock(BatchObjectStore.class);
    NamespacedObjectStore store = new NamespacedObjectStore(delegate, "blue");
    Instant modifiedAt = Instant.parse("2026-10-22T00:00:00Z");
    when(delegate.list("bucket", "blue/reports/", "blue/reports/a.csv", 10))
        .thenReturn(new ObjectListing(
            List.of(
                new ObjectSummary("blue/reports/b.csv", 12, modifiedAt, "b"),
                new ObjectSummary("red/reports/c.csv", 13, modifiedAt, "c")),
            "blue/reports/b.csv"));
    when(delegate.presign("bucket", "blue/reports/a.csv", Duration.ofMinutes(5)))
        .thenReturn("https://storage.example/blue/reports/a.csv");
    when(delegate.supportsPresignPut()).thenReturn(true);
    when(delegate.presignPut(
            "bucket", "blue/reports/upload.csv", Duration.ofMinutes(5), "text/csv"))
        .thenReturn("https://storage.example/blue/reports/upload.csv");

    store.put(
        "bucket",
        "reports/a.csv",
        new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)),
        4,
        "text/csv");
    store.copy("bucket", "reports/a.csv", "reports/copy.csv");
    store.delete("bucket", "reports/copy.csv");
    ObjectListing listing = store.list("bucket", "reports/", "reports/a.csv", 10);

    assertThat(listing.objects())
        .containsExactly(new ObjectSummary("reports/b.csv", 12, modifiedAt, "b"));
    assertThat(listing.nextMarker()).isEqualTo("reports/b.csv");
    assertThat(store.presign("bucket", "reports/a.csv", Duration.ofMinutes(5)))
        .contains("/blue/reports/a.csv");
    assertThat(store.supportsPresignPut()).isTrue();
    assertThat(store.presignPut("bucket", "reports/upload.csv", Duration.ofMinutes(5), "text/csv"))
        .contains("/blue/reports/upload.csv");

    verify(delegate)
        .put(
            org.mockito.ArgumentMatchers.eq("bucket"),
            org.mockito.ArgumentMatchers.eq("blue/reports/a.csv"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(4L),
            org.mockito.ArgumentMatchers.eq("text/csv"));
    verify(delegate).copy("bucket", "blue/reports/a.csv", "blue/reports/copy.csv");
    verify(delegate).delete("bucket", "blue/reports/copy.csv");
  }

  @Test
  @DisplayName("文件系统后端将对象写入 namespace 子目录且列举不泄漏其它部署")
  void shouldIsolateFilesystemObjects(@TempDir Path root) throws Exception {
    FilesystemObjectStore filesystem =
        new FilesystemObjectStore(root.toString(), "", "test-secret");
    NamespacedObjectStore blueStore = new NamespacedObjectStore(filesystem, "blue");
    NamespacedObjectStore redStore = new NamespacedObjectStore(filesystem, "red");
    byte[] payload = "blue-data".getBytes(StandardCharsets.UTF_8);

    blueStore.put(
        "bucket",
        "daily/result.csv",
        new ByteArrayInputStream(payload),
        payload.length,
        "text/csv");
    redStore.put(
        "bucket", "daily/other.csv", new ByteArrayInputStream(payload), payload.length, "text/csv");

    assertThat(Files.exists(root.resolve("bucket/blue/daily/result.csv"))).isTrue();
    assertThat(blueStore.exists("bucket", "daily/result.csv")).isTrue();
    assertThat(blueStore.list("bucket", "", null, 10).objects())
        .extracting(ObjectSummary::key)
        .containsExactly("daily/result.csv");
    assertThatThrownBy(() -> blueStore.get("bucket", "daily/other.csv"))
        .isInstanceOf(ObjectNotFoundException.class);
  }

  @DisplayName("拒绝非法对象存储命名空间")
  @Test
  void shouldRejectInvalidNamespace() {
    assertThatThrownBy(() -> new NamespacedObjectStore(mock(BatchObjectStore.class), "../blue"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");
  }
}
