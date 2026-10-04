package io.github.pinpols.batch.common.utils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Set;

/** 为包含业务数据或凭据的中间文件提供进程级私有临时目录。 */
public final class PrivateTempFiles {

  private static final String ROOT_DIRECTORY = "file-batch-private";
  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_DIRECTORY =
      PosixFilePermissions.asFileAttribute(Set.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE));
  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_FILE =
      PosixFilePermissions.asFileAttribute(
          Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

  private PrivateTempFiles() {}

  /** 在进程私有目录创建 owner-only 临时文件。 */
  public static Path createTempFile(String prefix, String suffix) throws IOException {
    return createTempFile(privateDirectory(), prefix, suffix);
  }

  /** 为不可续跑的临时副本持有进程锁，清理器不能删除仍在使用的文件。 */
  public static LockedTempFile createLockedTempFile(String prefix, String suffix)
      throws IOException {
    Path path = createTempFile(prefix, suffix);
    Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
    FileChannel channel = null;
    boolean acquired = false;
    try {
      createOwnerOnlyFile(lockPath);
      channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
      FileLock lock = channel.lock();
      acquired = true;
      return new LockedTempFile(path, lockPath, channel, lock);
    } finally {
      if (!acquired) {
        try {
          if (EmptyChecks.isNotNull(channel)) {
            channel.close();
          }
        } finally {
          try {
            Files.deleteIfExists(path);
          } finally {
            Files.deleteIfExists(lockPath);
          }
        }
      }
    }
  }

  public record LockedTempFile(Path path, Path lockPath, FileChannel channel, FileLock lock)
      implements AutoCloseable {
    @Override
    public void close() throws IOException {
      try {
        try {
          lock.close();
        } finally {
          channel.close();
        }
      } finally {
        try {
          Files.deleteIfExists(path);
        } finally {
          Files.deleteIfExists(lockPath);
        }
      }
    }
  }

  /** 只清理指定前缀的直接子文件；锁仍被持有时保留，不递归触碰可续跑目录。 */
  public static int deleteStaleUnlockedFiles(String prefix, Instant cutoff) throws IOException {
    int deleted = 0;
    try (DirectoryStream<Path> paths = Files.newDirectoryStream(privateDirectory(), prefix + "*")) {
      for (Path path : paths) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || path.getFileName().toString().endsWith(".lock")
            || !Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                .toInstant()
                .isBefore(cutoff)) {
          continue;
        }
        Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
        boolean stale;
        try {
          if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            stale = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                .toInstant()
                .isBefore(cutoff);
          } else {
            try (FileChannel channel = FileChannel.open(
                    lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FileLock lock = channel.tryLock()) {
              stale = EmptyChecks.isNotNull(lock)
                  && Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                      .toInstant()
                      .isBefore(cutoff);
            }
          }
        } catch (OverlappingFileLockException busy) {
          continue;
        } catch (NoSuchFileException removed) {
          continue;
        }
        // 文件名由创建者随机生成且不复用；关闭句柄后删除以兼容 Windows。
        if (stale && Files.deleteIfExists(path)) {
          Files.deleteIfExists(lockPath);
          deleted++;
        }
      }
    }
    return deleted;
  }

  /** 在进程私有目录创建 owner-only 临时工作目录。 */
  public static Path createTempDirectory(String prefix) throws IOException {
    Path directory = privateDirectory();
    try {
      return Files.createTempDirectory(directory, prefix, OWNER_ONLY_DIRECTORY);
    } catch (UnsupportedOperationException ignored) {
      Path path = Files.createTempDirectory(directory, prefix);
      setOwnerOnlyPermissions(path, true);
      return path;
    }
  }

  private static Path createTempFile(Path directory, String prefix, String suffix)
      throws IOException {
    try {
      return Files.createTempFile(directory, prefix, suffix, OWNER_ONLY_FILE);
    } catch (UnsupportedOperationException ignored) {
      Path path = Files.createTempFile(directory, prefix, suffix);
      setOwnerOnlyPermissions(path, false);
      return path;
    }
  }

  private static void createOwnerOnlyFile(Path path) throws IOException {
    try {
      Files.createFile(path, OWNER_ONLY_FILE);
    } catch (UnsupportedOperationException ignored) {
      Files.createFile(path);
      setOwnerOnlyPermissions(path, false);
    }
  }

  private static Path privateDirectory() throws IOException {
    Path directory = Path.of(System.getProperty("java.io.tmpdir"), ROOT_DIRECTORY);
    try {
      Files.createDirectory(directory, OWNER_ONLY_DIRECTORY);
    } catch (UnsupportedOperationException ignored) {
      try {
        Files.createDirectory(directory);
      } catch (FileAlreadyExistsException alreadyExists) {
        // Existing directory is validated below.
      }
    } catch (FileAlreadyExistsException alreadyExists) {
      // Existing directory is validated below.
    }
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("private temp path is not a directory: " + directory);
    }
    setOwnerOnlyPermissions(directory, true);
    return directory;
  }

  private static void setOwnerOnlyPermissions(Path path, boolean directory) throws IOException {
    try {
      Files.setPosixFilePermissions(
          path,
          directory
              ? Set.of(
                  PosixFilePermission.OWNER_READ,
                  PosixFilePermission.OWNER_WRITE,
                  PosixFilePermission.OWNER_EXECUTE)
              : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException ignored) {
      // Windows ACLs and non-POSIX filesystems enforce permissions outside this API.
    }
  }
}
