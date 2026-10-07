package io.github.pinpols.batch.common.storage;

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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link FilesystemObjectStore} 单测：覆盖 §4 三大难点 + traversal + 异常映射 + presign sign/verify。 */
@DisplayName("文件系统对象存储:验证读写往返、切片范围读取无损、列举分页与扫描上限、临时与隐藏文件过滤、长度校验、路径穿越拒绝、异常映射与预签名签验")
class FilesystemObjectStoreTest {

  private static final String BUCKET = "test-bucket";
  private static final String SECRET = "test-presign-secret-1234567890";
  private static final String DOWNLOAD_BASE_URL =
      "https://example.invalid/api/console/files/fs-download";

  private FilesystemObjectStore newStore(Path root) {
    return new FilesystemObjectStore(root.toString(), DOWNLOAD_BASE_URL, SECRET);
  }

  @Test
  @DisplayName("写入后可读回完全一致的字节,存在性与大小统计与写入内容一致")
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
  @DisplayName("批量删除移除全部已存在对象,缺失的键不导致失败")
  void shouldRemoveAllKeys_whenBatchDeleteIncludesMissingKey(@TempDir Path root) {
    FilesystemObjectStore store = newStore(root);
    byte[] p = "x".getBytes(StandardCharsets.UTF_8);
    for (String k : new String[] {"d/a.txt", "d/b.txt", "d/c.txt"}) {
      store.put(BUCKET, k, new ByteArrayInputStream(p), p.length, "text/plain");
    }
    // 文件系统后端不支持直传 PUT 预签名
    assertThat(store.supportsPresignPut()).isFalse();

    store.deleteMany(BUCKET, List.of("d/a.txt", "d/b.txt", "d/c.txt", "d/missing.txt"));

    assertThat(store.exists(BUCKET, "d/a.txt")).isFalse();
    assertThat(store.exists(BUCKET, "d/b.txt")).isFalse();
    assertThat(store.exists(BUCKET, "d/c.txt")).isFalse();
  }

  @Test
  @DisplayName("写入完成后不关闭调用方传入的输入流")
  void shouldLeaveCallerStreamOpen_whenPutCompletes(@TempDir Path root) {
    FilesystemObjectStore store = newStore(root);
    byte[] payload = "caller-owned".getBytes(StandardCharsets.UTF_8);
    CloseTrackingInputStream inputStream = new CloseTrackingInputStream(payload);

    store.put(BUCKET, "owned.txt", inputStream, payload.length, "text/plain");

    assertThat(inputStream.closed).isFalse();
  }

  @Test
  @DisplayName("声明长度大于实际字节数时拒绝写入,底层不落文件")
  void shouldRejectWrite_whenDeclaredLengthExceedsActualBytes(@TempDir Path root) {
    FilesystemObjectStore store = newStore(root);
    byte[] payload = "short".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() ->
            store.put(BUCKET, "short.txt", new ByteArrayInputStream(payload), 99, "text/plain"))
        .isInstanceOf(ObjectStoreException.class)
        .hasMessageContaining("length mismatch");
    assertThat(store.exists(BUCKET, "short.txt")).isFalse();
  }

  @Test
  @DisplayName("实际字节数多于声明长度时拒绝写入,底层不落文件")
  void shouldRejectWrite_whenActualBytesExceedDeclaredLength(@TempDir Path root) {
    FilesystemObjectStore store = newStore(root);
    byte[] payload = "longer-than-declared".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(
            () -> store.put(BUCKET, "long.txt", new ByteArrayInputStream(payload), 4, "text/plain"))
        .isInstanceOf(ObjectStoreException.class)
        .hasMessageContaining("length mismatch");
    assertThat(store.exists(BUCKET, "long.txt")).isFalse();
  }

  @Test
  @DisplayName("从任意偏移读取返回剩余内容,偏移等于总长度时读到流末尾")
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
  @DisplayName("按多组切片偏移依次读取并拼接,结果与原文完全一致且不重不漏")
  void shouldConcatenateSlicesLosslessly_whenReadingByOffsets(@TempDir Path root) throws Exception {
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
  @DisplayName("列举只返回完整对象,未完成的临时文件与隐藏文件不可见")
  void shouldHideTempAndHiddenEntries_whenListing(@TempDir Path root) throws Exception {
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
  @DisplayName("按单页上限分页返回,游标指向末条键,最后一页游标为空")
  void shouldPaginateByMaxKeys_whenListingMoreThanOnePage(@TempDir Path root) {
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
  @DisplayName("目录扫描条目数超过上限时拒绝列举并给出超限提示")
  void shouldRejectListing_whenScannedEntriesExceedLimit(@TempDir Path root) {
    FilesystemObjectStore store =
        new FilesystemObjectStore(root.toString(), DOWNLOAD_BASE_URL, SECRET, 2);
    for (int i = 0; i < 3; i++) {
      byte[] b = new byte[] {(byte) i};
      store.put(BUCKET, "wide/k" + i, new ByteArrayInputStream(b), b.length, "x");
    }

    assertThatThrownBy(() -> store.list(BUCKET, "wide/", null, 10))
        .isInstanceOf(ObjectStoreException.class)
        .hasMessageContaining("maxListScanEntries");
  }

  @Test
  @DisplayName("扫描上限统计包含隐藏文件与临时文件,超限即拒绝列举")
  void shouldCountHiddenAndTempEntriesTowardScanLimit_whenListing(@TempDir Path root)
      throws Exception {
    FilesystemObjectStore store =
        new FilesystemObjectStore(root.toString(), DOWNLOAD_BASE_URL, SECRET, 2);
    Path bucketDir = root.resolve(BUCKET).resolve("wide");
    Files.createDirectories(bucketDir);
    Files.createFile(bucketDir.resolve(".hidden-1"));
    Files.createFile(
        bucketDir.resolve("payload.csv" + FilesystemObjectStore.TEMP_SUFFIX_MARKER + "1"));
    Files.createFile(
        bucketDir.resolve("payload.csv" + FilesystemObjectStore.TEMP_SUFFIX_MARKER + "2"));

    assertThatThrownBy(() -> store.list(BUCKET, "wide/", null, 10))
        .isInstanceOf(ObjectStoreException.class)
        .hasMessageContaining("maxListScanEntries");
  }

  @Test
  @DisplayName("对象内容与修改时间变化后,其标识随之变化")
  void shouldChangeEtag_whenObjectRewritten(@TempDir Path root) throws Exception {
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
  @DisplayName("含上级目录跳转或绝对路径的键被拒绝,写入与读取均抛异常")
  void shouldRejectTraversalKeys(@TempDir Path root) {
    FilesystemObjectStore store = newStore(root);
    assertThatThrownBy(() ->
            store.put(BUCKET, "../escape", new ByteArrayInputStream(new byte[0]), 0, "text/plain"))
        .isInstanceOf(ObjectStoreException.class);
    assertThatThrownBy(() -> store.get(BUCKET, "/abs/path"))
        .isInstanceOf(ObjectStoreException.class);
  }

  @Test
  @DisplayName("键不存在时读取与大小统计抛出未找到异常,存在性判断返回不存在")
  void shouldSignalNotFound_whenKeyMissing(@TempDir Path root) {
    FilesystemObjectStore store = newStore(root);
    assertThatThrownBy(() -> store.get(BUCKET, "missing"))
        .isInstanceOf(ObjectNotFoundException.class);
    assertThatThrownBy(() -> store.statSize(BUCKET, "missing"))
        .isInstanceOf(ObjectNotFoundException.class);
    assertThat(store.exists(BUCKET, "missing")).isFalse();
  }

  @Test
  @DisplayName("复制后目标键的内容与源对象完全一致")
  void shouldCopyContent_whenTargetKeyProvided(@TempDir Path root) throws IOException {
    FilesystemObjectStore store = newStore(root);
    byte[] payload = "copy-me".getBytes(StandardCharsets.UTF_8);
    store.put(BUCKET, "src", new ByteArrayInputStream(payload), payload.length, "x");
    store.copy(BUCKET, "src", "dst");
    try (InputStream in = store.get(BUCKET, "dst")) {
      assertThat(in.readAllBytes()).isEqualTo(payload);
    }
  }

  @Test
  @DisplayName("删除后对象不可见,重复删除保持幂等不报错")
  void shouldRemoveObjectOnce_whenDeleteCalled(@TempDir Path root) {
    FilesystemObjectStore store = newStore(root);
    store.put(BUCKET, "x", new ByteArrayInputStream(new byte[] {1}), 1, "x");
    store.delete(BUCKET, "x");
    assertThat(store.exists(BUCKET, "x")).isFalse();
    // 幂等
    store.delete(BUCKET, "x");
  }

  @Test
  @DisplayName("未过期的原始签名校验通过,篡改签名、篡改桶名或已过期均校验失败")
  void shouldVerifySignature_whenTokenUntamperedAndUnexpired(@TempDir Path root) throws Exception {
    FilesystemObjectStore store = newStore(root);
    store.put(BUCKET, "f.txt", new ByteArrayInputStream(new byte[] {1}), 1, "x");

    String url = store.presign(BUCKET, "f.txt", Duration.ofMinutes(5));
    assertThat(url).startsWith(DOWNLOAD_BASE_URL).contains("b=test-bucket").contains("k=f.txt");

    // 提取 e 和 s 并 verify
    long exp = Long.parseLong(extractParam(url, "e"));
    String sig = extractParam(url, "s");
    assertThat(FilesystemPresignTokens.verify(BUCKET, "f.txt", exp, sig, SECRET))
        .isTrue();

    // 篡改 sig
    assertThat(FilesystemPresignTokens.verify(BUCKET, "f.txt", exp, "tampered", SECRET))
        .isFalse();
    // 篡改 bucket
    assertThat(FilesystemPresignTokens.verify("other", "f.txt", exp, sig, SECRET))
        .isFalse();
    // 过期
    String pastSig =
        FilesystemPresignTokens.sign(BUCKET, "f.txt", Instant.now().minusSeconds(60), SECRET);
    assertThat(FilesystemPresignTokens.verify(
            BUCKET, "f.txt", Instant.now().getEpochSecond() - 60, pastSig, SECRET))
        .isFalse();
  }

  @Test
  @DisplayName("未指定有效期时采用配置的默认值,剩余有效时间落在预期区间")
  void shouldUseConfiguredDefaultTtl_whenTtlMissing(@TempDir Path root) {
    FilesystemObjectStore store = new FilesystemObjectStore(
        root.toString(), DOWNLOAD_BASE_URL, SECRET, Duration.ofMinutes(2), 200_000);

    String url = store.presign(BUCKET, "default-ttl.txt", null);
    long remainingSeconds =
        Long.parseLong(extractParam(url, "e")) - Instant.now().getEpochSecond();

    assertThat(remainingSeconds).isBetween(115L, 120L);
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

  private static final class CloseTrackingInputStream extends ByteArrayInputStream {
    private boolean closed;

    private CloseTrackingInputStream(byte[] buf) {
      super(buf);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
