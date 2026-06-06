package com.example.batch.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link FilesystemObjectStore} 单测：覆盖 §4 三大难点 + traversal + 异常映射 + presign sign/verify。 */
class FilesystemObjectStoreTest {

  private static final String BUCKET = "test-bucket";
  private static final String SECRET = "test-presign-secret-1234567890";
  private static final String DOWNLOAD_BASE_URL =
      "https://example.invalid/api/console/files/fs-download";

  private FilesystemObjectStore newStore(Path root) {
    return new FilesystemObjectStore(root.toString(), DOWNLOAD_BASE_URL, SECRET);
  }

  @Test
  void shouldRoundTripPutAndGet(@TempDir Path root) throws Exception {
    FilesystemObjectStore store = newStore(root);
    byte[] payload = "hello-fs-world".getBytes(StandardCharsets.UTF_8);

    store.put(BUCKET, "dir/a.txt", new ByteArrayInputStream(payload), payload.length, "text/plain");

    try (InputStream in = store.get(BUCKET, "dir/a.txt")) {
      assertThat(in.readAllBytes()).isEqualTo(payload);
    }
    assertThat(store.exists(BUCKET, "dir/a.txt")).isTrue();
    assertThat(store.statSize(BUCKET, "dir/a.txt")).isEqualTo(payload.length);
  }

  @Test
  void shouldReadFromArbitraryOffset(@TempDir Path root) throws Exception {
    FilesystemObjectStore store = newStore(root);
    byte[] payload = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
    store.put(BUCKET, "k", new ByteArrayInputStream(payload), payload.length, "text/plain");

    try (InputStream in = store.getFrom(BUCKET, "k", 7)) {
      assertThat(in.readAllBytes()).isEqualTo("789ABCDEF".getBytes(StandardCharsets.UTF_8));
    }
    try (InputStream in = store.getFrom(BUCKET, "k", payload.length)) {
      assertThat(in.read()).isEqualTo(-1);
    }
  }

  /** §4② 关键：N 个 offset 切片按序拼接 == 原文，等价于无重叠 + 无遗漏 + 不劈位。 */
  @Test
  void getFromShouldBeLosslessAcrossLineBoundaries(@TempDir Path root) throws Exception {
    FilesystemObjectStore store = newStore(root);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 50; i++) {
      sb.append(String.format("%05d", i)).append("ABCDEFGHIJ").append('\n');
    }
    String content = sb.toString();
    byte[] data = content.getBytes(StandardCharsets.UTF_8);
    store.put(BUCKET, "lines.txt", new ByteArrayInputStream(data), data.length, "text/plain");

    for (int n : new int[] {1, 2, 3, 5, 7, 13}) {
      ByteArrayOutputStream concat = new ByteArrayOutputStream();
      long s = data.length;
      for (int p = 1; p <= n; p++) {
        long rawStart = s * (p - 1) / n;
        long rawEnd = p == n ? s : s * p / n;
        try (InputStream in = store.getFrom(BUCKET, "lines.txt", rawStart)) {
          byte[] buf = new byte[(int) (rawEnd - rawStart)];
          int read = 0;
          while (read < buf.length) {
            int got = in.read(buf, read, buf.length - read);
            if (got < 0) {
              break;
            }
            read += got;
          }
          concat.write(buf, 0, read);
        }
      }
      assertThat(new String(concat.toByteArray(), StandardCharsets.UTF_8))
          .as("N=%d offset 切片拼接应无损还原原文", n)
          .isEqualTo(content);
    }
  }

  /** §4③ 关键：put 原子性 → list 不读到正在写的 .tmp.xxx 文件，对外只见完整对象。 */
  @Test
  void listShouldSkipTempAndHiddenFiles(@TempDir Path root) throws Exception {
    FilesystemObjectStore store = newStore(root);
    // 准备一个正式对象
    store.put(BUCKET, "ok.txt", new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, "x");
    // 模拟一个未完成的 tmp 文件直接落在 bucket 目录
    Path bucketDir = root.resolve(BUCKET);
    Files.createFile(
        bucketDir.resolve("ok.txt" + FilesystemObjectStore.TEMP_SUFFIX_MARKER + "abc"));
    Files.createFile(bucketDir.resolve(".hidden"));

    ObjectListing listing = store.list(BUCKET, "", null, 100);
    assertThat(listing.objects()).extracting(ObjectSummary::key).containsExactly("ok.txt");
    assertThat(listing.nextMarker()).isNull();
  }

  @Test
  void listShouldPaginateByMaxKeysWithMarker(@TempDir Path root) throws Exception {
    FilesystemObjectStore store = newStore(root);
    for (int i = 0; i < 5; i++) {
      byte[] b = new byte[] {(byte) i};
      store.put(BUCKET, "k" + i, new ByteArrayInputStream(b), b.length, "x");
    }

    ObjectListing p1 = store.list(BUCKET, "", null, 2);
    assertThat(p1.objects()).extracting(ObjectSummary::key).containsExactly("k0", "k1");
    assertThat(p1.nextMarker()).isEqualTo("k1");

    ObjectListing p2 = store.list(BUCKET, "", p1.nextMarker(), 2);
    assertThat(p2.objects()).extracting(ObjectSummary::key).containsExactly("k2", "k3");
    assertThat(p2.nextMarker()).isEqualTo("k3");

    ObjectListing p3 = store.list(BUCKET, "", p2.nextMarker(), 2);
    assertThat(p3.objects()).extracting(ObjectSummary::key).containsExactly("k4");
    assertThat(p3.nextMarker()).isNull();
  }

  @Test
  void etagShouldReflectSizeAndMtime(@TempDir Path root) throws Exception {
    FilesystemObjectStore store = newStore(root);
    byte[] before = "abc".getBytes(StandardCharsets.UTF_8);
    store.put(BUCKET, "et.txt", new ByteArrayInputStream(before), before.length, "x");
    String etagBefore = store.list(BUCKET, "et.txt", null, 1).objects().get(0).etag();

    // 等 mtime 步进至少 10ms 再改写
    Thread.sleep(20L);
    byte[] after = "abcd".getBytes(StandardCharsets.UTF_8);
    store.put(BUCKET, "et.txt", new ByteArrayInputStream(after), after.length, "x");
    String etagAfter = store.list(BUCKET, "et.txt", null, 1).objects().get(0).etag();

    assertThat(etagAfter).isNotEqualTo(etagBefore);
  }

  @Test
  void shouldRejectTraversalKeys(@TempDir Path root) {
    FilesystemObjectStore store = newStore(root);
    assertThatThrownBy(
            () ->
                store.put(
                    BUCKET, "../escape", new ByteArrayInputStream(new byte[0]), 0, "text/plain"))
        .isInstanceOf(ObjectStoreException.class);
    assertThatThrownBy(() -> store.get(BUCKET, "/abs/path"))
        .isInstanceOf(ObjectStoreException.class);
  }

  @Test
  void getNonExistentKeyShouldThrowNotFound(@TempDir Path root) {
    FilesystemObjectStore store = newStore(root);
    assertThatThrownBy(() -> store.get(BUCKET, "missing"))
        .isInstanceOf(ObjectNotFoundException.class);
    assertThatThrownBy(() -> store.statSize(BUCKET, "missing"))
        .isInstanceOf(ObjectNotFoundException.class);
    assertThat(store.exists(BUCKET, "missing")).isFalse();
  }

  @Test
  void copyShouldDuplicateObject(@TempDir Path root) throws IOException {
    FilesystemObjectStore store = newStore(root);
    byte[] payload = "copy-me".getBytes(StandardCharsets.UTF_8);
    store.put(BUCKET, "src", new ByteArrayInputStream(payload), payload.length, "x");
    store.copy(BUCKET, "src", "dst");
    try (InputStream in = store.get(BUCKET, "dst")) {
      assertThat(in.readAllBytes()).isEqualTo(payload);
    }
  }

  @Test
  void deleteShouldRemoveObject(@TempDir Path root) throws IOException {
    FilesystemObjectStore store = newStore(root);
    store.put(BUCKET, "x", new ByteArrayInputStream(new byte[] {1}), 1, "x");
    store.delete(BUCKET, "x");
    assertThat(store.exists(BUCKET, "x")).isFalse();
    // 幂等
    store.delete(BUCKET, "x");
  }

  @Test
  void presignShouldRoundTripSignAndVerify(@TempDir Path root) throws Exception {
    FilesystemObjectStore store = newStore(root);
    store.put(BUCKET, "f.txt", new ByteArrayInputStream(new byte[] {1}), 1, "x");

    String url = store.presign(BUCKET, "f.txt", Duration.ofMinutes(5));
    assertThat(url).startsWith(DOWNLOAD_BASE_URL).contains("b=test-bucket").contains("k=f.txt");

    // 提取 e 和 s 并 verify
    long exp = Long.parseLong(extractParam(url, "e"));
    String sig = extractParam(url, "s");
    assertThat(FilesystemPresignTokens.verify(BUCKET, "f.txt", exp, sig, SECRET)).isTrue();

    // 篡改 sig
    assertThat(FilesystemPresignTokens.verify(BUCKET, "f.txt", exp, "tampered", SECRET)).isFalse();
    // 篡改 bucket
    assertThat(FilesystemPresignTokens.verify("other", "f.txt", exp, sig, SECRET)).isFalse();
    // 过期
    String pastSig =
        FilesystemPresignTokens.sign(BUCKET, "f.txt", Instant.now().minusSeconds(60), SECRET);
    assertThat(
            FilesystemPresignTokens.verify(
                BUCKET, "f.txt", Instant.now().getEpochSecond() - 60, pastSig, SECRET))
        .isFalse();
  }

  private static String extractParam(String url, String name) {
    int q = url.indexOf('?');
    String query = url.substring(q + 1);
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(name)) {
        return pair.substring(eq + 1);
      }
    }
    throw new IllegalArgumentException("missing param: " + name);
  }
}
