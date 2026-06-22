package com.example.batch.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * 文件系统对象存储后端（Phase 2 §4）。映射：{@code bucket → root/<bucket>/} 目录；{@code key} → 该目录下相对路径。
 *
 * <p>三大难点解法（对标 Hadoop / Iceberg / Rails ActiveStorage / jclouds filesystem provider）：
 *
 * <ul>
 *   <li><b>put 原子写</b>：写到 {@code <final>.tmp.<uuid>} → fsync → {@code ATOMIC_MOVE}；NFS 不支持原子 rename
 *       时 fallback {@code REPLACE_EXISTING} 并 warn。
 *   <li><b>getFrom(offset)</b>：{@code FileChannel.position(offset) + Channels.newInputStream}（关联
 *       stream close 会级联关闭 channel）。
 *   <li><b>list etag</b>：{@code size + mtime} 合成伪 etag（变更检测足够；内容完整性走独立 sha256，不在此接口）。
 *   <li><b>presign</b>：本地无存储直发签名 → 签 HMAC 能力令牌 + 应用代下端点 URL。
 * </ul>
 *
 * <p>安全：key 含 {@code ..} 直接拒绝；规范化后必须仍在 {@code root/<bucket>/} 内（与 {@code
 * DispatchFileContentResolver} traversal 校验语义对齐）。
 */
@Slf4j
public class FilesystemObjectStore implements BatchObjectStore {

  /** 临时文件名后缀前缀；list 时跳过这些与 . 开头的隐藏文件（§4③）。 */
  static final String TEMP_SUFFIX_MARKER = ".tmp.";

  private final Path root;
  private final String downloadBaseUrl;
  private final String presignSecret;

  public FilesystemObjectStore(String root, String downloadBaseUrl, String presignSecret) {
    if (root == null || root.isBlank()) {
      throw new IllegalArgumentException("filesystem storage root must not be blank");
    }
    this.root = Paths.get(root).toAbsolutePath().normalize();
    this.downloadBaseUrl = downloadBaseUrl;
    this.presignSecret = presignSecret;
    try {
      Files.createDirectories(this.root);
    } catch (IOException ex) {
      throw new ObjectStoreException("failed to create filesystem storage root: " + this.root, ex);
    }
  }

  @Override
  public void put(String bucket, String key, InputStream in, long size, String contentType) {
    Path target = resolveKey(bucket, key);
    Path temp = null;
    try {
      Files.createDirectories(target.getParent());
      temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX_MARKER + UUID.randomUUID());
      try (FileChannel ch =
          FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        ExactSizeInputStream exact =
            ExactSizeInputStream.exact(in, "filesystem", bucket, key, size);
        exact.transferTo(Channels.newOutputStream(ch));
        exact.verifyFullyRead();
        // 强制 metadata + data 落盘，避免 rename 后崩溃丢数据
        ch.force(true);
      }
      try {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException atomicEx) {
        log.warn(
            "filesystem store ATOMIC_MOVE not supported, falling back to REPLACE_EXISTING:"
                + " bucket={} key={}",
            bucket,
            key);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException moveEx) {
        // best-effort 清理临时文件
        Files.deleteIfExists(temp);
        throw moveEx;
      }
    } catch (ObjectStoreException ex) {
      cleanupTemp(temp);
      throw ex;
    } catch (IOException | UncheckedIOException ex) {
      cleanupTemp(temp);
      throw mapException("put", bucket, key, ex);
    }
  }

  @Override
  public void copy(String bucket, String srcKey, String dstKey) {
    Path src = resolveKey(bucket, srcKey);
    Path dst = resolveKey(bucket, dstKey);
    Path temp = null;
    try {
      if (!Files.exists(src)) {
        throw new NoSuchFileException(src.toString());
      }
      Files.createDirectories(dst.getParent());
      temp = dst.resolveSibling(dst.getFileName() + TEMP_SUFFIX_MARKER + UUID.randomUUID());
      Files.copy(src, temp, StandardCopyOption.COPY_ATTRIBUTES);
      try {
        Files.move(temp, dst, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException atomicEx) {
        log.warn(
            "filesystem copy ATOMIC_MOVE not supported, falling back to REPLACE_EXISTING: bucket={}"
                + " dst={}",
            bucket,
            dstKey);
        Files.move(temp, dst, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException | UncheckedIOException ex) {
      // 任何失败(copy 或 move 阶段)都清理临时文件,避免残留 .tmp 文件泄漏。
      cleanupTemp(temp);
      throw mapException("copy", bucket, srcKey, ex);
    }
  }

  @Override
  public void delete(String bucket, String key) {
    Path target = resolveKey(bucket, key);
    try {
      Files.deleteIfExists(target);
    } catch (IOException ex) {
      throw mapException("delete", bucket, key, ex);
    }
  }

  @Override
  public InputStream get(String bucket, String key) {
    Path target = resolveKey(bucket, key);
    try {
      return Files.newInputStream(target, StandardOpenOption.READ);
    } catch (IOException ex) {
      throw mapException("get", bucket, key, ex);
    }
  }

  @Override
  public InputStream getFrom(String bucket, String key, long offset) {
    Path target = resolveKey(bucket, key);
    try {
      FileChannel ch = FileChannel.open(target, StandardOpenOption.READ);
      try {
        ch.position(offset);
      } catch (IOException positionEx) {
        ch.close();
        throw positionEx;
      }
      // Channels.newInputStream 的 close 会级联 close 底层 channel（JDK 标准实现）。
      return Channels.newInputStream(ch);
    } catch (IOException ex) {
      throw mapException("getFrom", bucket, key, ex);
    }
  }

  @Override
  public long statSize(String bucket, String key) {
    Path target = resolveKey(bucket, key);
    try {
      return Files.size(target);
    } catch (IOException ex) {
      throw mapException("statSize", bucket, key, ex);
    }
  }

  @Override
  public boolean exists(String bucket, String key) {
    Path target = resolveKey(bucket, key);
    return Files.isRegularFile(target);
  }

  @Override
  public ObjectListing list(String bucket, String prefix, String afterMarker, int maxKeys) {
    if (maxKeys <= 0) {
      return new ObjectListing(List.of(), null);
    }
    Path bucketRoot = resolveBucketRoot(bucket);
    if (!Files.isDirectory(bucketRoot)) {
      return new ObjectListing(List.of(), null);
    }
    String safePrefix = prefix == null ? "" : prefix;
    if (safePrefix.contains("..") || safePrefix.startsWith("/")) {
      throw new ObjectStoreException(
          "filesystem object list prefix contains illegal traversal sequence: " + safePrefix);
    }
    Path scanRoot = resolvePrefixScanRoot(bucketRoot, safePrefix);
    if (!Files.isDirectory(scanRoot)) {
      return new ObjectListing(List.of(), null);
    }
    PriorityQueue<ObjectSummary> smallestKeys =
        new PriorityQueue<>(Comparator.comparing(ObjectSummary::key).reversed());
    try (Stream<Path> walk = Files.walk(scanRoot)) {
      Iterator<Path> iterator =
          walk.filter(Files::isRegularFile).filter(path -> !isHiddenOrTemp(path)).iterator();
      while (iterator.hasNext()) {
        Path p = iterator.next();
        String relKey = relativeKey(bucketRoot, p);
        if (!relKey.startsWith(safePrefix)) {
          continue;
        }
        if (afterMarker != null && relKey.compareTo(afterMarker) <= 0) {
          continue;
        }
        long size = Files.size(p);
        FileTime mtime = Files.getLastModifiedTime(p);
        smallestKeys.add(
            new ObjectSummary(
                relKey, size, mtime.toInstant(), syntheticEtag(size, mtime.toInstant())));
        if (smallestKeys.size() > maxKeys + 1) {
          smallestKeys.poll();
        }
      }
    } catch (IOException | UncheckedIOException ex) {
      throw mapException("list", bucket, safePrefix, ex);
    }
    List<ObjectSummary> sorted = new ArrayList<>(smallestKeys);
    sorted.sort(Comparator.comparing(ObjectSummary::key));
    boolean hasMore = sorted.size() > maxKeys;
    List<ObjectSummary> page = hasMore ? new ArrayList<>(sorted.subList(0, maxKeys)) : sorted;
    String nextMarker = hasMore && !page.isEmpty() ? page.get(page.size() - 1).key() : null;
    return new ObjectListing(List.copyOf(page), nextMarker);
  }

  @Override
  public String presign(String bucket, String key, Duration ttl) {
    // 校验 key 合法性 + 在 root 内（即便不真读文件也应防止生成穿越 URL）
    resolveKey(bucket, key);
    if (downloadBaseUrl == null || downloadBaseUrl.isBlank()) {
      throw new ObjectStoreException(
          "filesystem presign requires batch.storage.filesystem.download-base-url");
    }
    if (presignSecret == null || presignSecret.isBlank()) {
      throw new ObjectStoreException(
          "filesystem presign requires non-blank secret (set"
              + " batch.storage.filesystem.presign-secret or batch.security.internal-secret)");
    }
    Instant expiresAt = Instant.now().plus(ttl);
    String sig = FilesystemPresignTokens.sign(bucket, key, expiresAt, presignSecret);
    return FilesystemPresignTokens.buildUrl(
        downloadBaseUrl, bucket, key, expiresAt.getEpochSecond(), sig);
  }

  /** 解析 key 对应的 Path 并做 traversal 校验。 */
  Path resolveKey(String bucket, String key) {
    if (key == null || key.isBlank()) {
      throw new ObjectStoreException("filesystem object key must not be blank");
    }
    if (key.contains("..") || key.startsWith("/")) {
      throw new ObjectStoreException(
          "filesystem object key contains illegal traversal sequence: " + key);
    }
    Path bucketRoot = resolveBucketRoot(bucket);
    Path resolved = bucketRoot.resolve(key).normalize();
    if (!resolved.startsWith(bucketRoot)) {
      throw new ObjectStoreException(
          "filesystem object key resolved outside bucket root: bucket=" + bucket + ", key=" + key);
    }
    return resolved;
  }

  private Path resolveBucketRoot(String bucket) {
    if (bucket == null || bucket.isBlank()) {
      throw new ObjectStoreException("filesystem bucket must not be blank");
    }
    if (bucket.contains("..") || bucket.contains("/") || bucket.contains("\\")) {
      throw new ObjectStoreException("filesystem bucket name illegal: " + bucket);
    }
    Path bucketRoot = root.resolve(bucket).normalize();
    if (!bucketRoot.startsWith(root)) {
      throw new ObjectStoreException("filesystem bucket escapes root: " + bucket);
    }
    return bucketRoot;
  }

  private String relativeKey(Path bucketRoot, Path path) {
    return bucketRoot.relativize(path).toString().replace('\\', '/');
  }

  private Path resolvePrefixScanRoot(Path bucketRoot, String safePrefix) {
    int slash = safePrefix.lastIndexOf('/');
    if (slash < 0) {
      return bucketRoot;
    }
    String directoryPrefix = safePrefix.substring(0, slash);
    if (directoryPrefix.isBlank()) {
      return bucketRoot;
    }
    Path resolved = bucketRoot.resolve(directoryPrefix).normalize();
    if (!resolved.startsWith(bucketRoot)) {
      throw new ObjectStoreException(
          "filesystem object list prefix resolved outside bucket root: " + safePrefix);
    }
    return resolved;
  }

  private boolean isHiddenOrTemp(Path path) {
    String name = path.getFileName().toString();
    if (name.startsWith(".")) {
      return true;
    }
    return name.contains(TEMP_SUFFIX_MARKER);
  }

  private String syntheticEtag(long size, Instant mtime) {
    return size + "-" + mtime.toEpochMilli();
  }

  private void cleanupTemp(Path temp) {
    if (temp == null) {
      return;
    }
    try {
      Files.deleteIfExists(temp);
    } catch (IOException ignored) {
      log.warn("filesystem object store failed to cleanup temp file: {}", temp, ignored);
    }
  }

  private ObjectStoreException mapException(
      String operation, String bucket, String key, Throwable ex) {
    Throwable cause = ex instanceof UncheckedIOException u ? u.getCause() : ex;
    String message =
        "filesystem object store %s failed: bucket=%s, key=%s".formatted(operation, bucket, key);
    if (cause instanceof NoSuchFileException) {
      return new ObjectNotFoundException(message, cause);
    }
    if (cause instanceof AccessDeniedException) {
      return new ObjectStoreAccessException(message, cause);
    }
    return new ObjectStoreException(message, cause);
  }
}
