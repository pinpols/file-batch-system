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

/**
 * 临时目录策略的唯一入口：既为包含业务数据或凭据的中间文件提供进程级私有临时目录，也集中解析各模块本地路径
 * 默认值所依赖的 {@code java.io.tmpdir} 根目录，避免该 JVM 系统属性被分散读取。
 *
 * <p>只读取 JVM 系统属性并做路径拼接，<b>不</b>把 {@code java.io.tmpdir} 包装成业务配置项（配置 Key 治理文档
 * §7）；业务侧如需可配置的落盘目录，仍由各自的 {@code @ConfigurationProperties} 承载。
 */
public final class PrivateTempFiles {

  /** JVM 临时目录系统属性名（{@code java.io.tmpdir}）：测试改属性时复用同一份 key，避免字面量漂移。 */
  public static final String TEMP_ROOT_PROPERTY = "java.io.tmpdir";

  private static final String ROOT_DIRECTORY = "file-batch-private";
  private static final String LOCK_SUFFIX = ".lock";
  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_DIRECTORY =
      PosixFilePermissions.asFileAttribute(Set.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE));
  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_FILE =
      PosixFilePermissions.asFileAttribute(
          Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

  private PrivateTempFiles() {}

  /** JVM 临时目录根（{@code java.io.tmpdir}）；供需要遍历临时目录的清理逻辑复用。 */
  public static Path tempRoot() {
    return Path.of(System.getProperty(TEMP_ROOT_PROPERTY));
  }

  /** 解析 JVM 临时目录下的子目录路径；供各模块本地路径默认值统一复用。 */
  public static Path resolveUnderTempRoot(String subdirectory) {
    return Path.of(System.getProperty(TEMP_ROOT_PROPERTY), subdirectory);
  }

  /** 在进程私有目录创建 owner-only 临时文件。 */
  public static Path createTempFile(String prefix, String suffix) throws IOException {
    return createTempFile(privateDirectory(), prefix, suffix);
  }

  /** 为不可续跑的临时副本持有进程锁，清理器不能删除仍在使用的文件。 */
  @SuppressWarnings("java:S2093") // 锁句柄转移给返回对象，工厂返回时关闭会失去活跃文件保护。
  public static LockedTempFile createLockedTempFile(String prefix, String suffix)
      throws IOException {
    Path path = createTempFile(prefix, suffix);
    Path lockPath = path.resolveSibling(path.getFileName() + LOCK_SUFFIX);
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
          if (channel != null) { // empty-check: allow - Sonar 需直接识别句柄解引用前的空值保护。
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
        if (deleteStaleUnlockedFile(path, cutoff)) {
          deleted++;
        }
      }
    }
    return deleted;
  }

  private static boolean deleteStaleUnlockedFile(Path path, Instant cutoff) throws IOException {
    Path lockPath = path.resolveSibling(path.getFileName() + LOCK_SUFFIX);
    try {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
          || path.getFileName().toString().endsWith(LOCK_SUFFIX)
          || !Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
              .toInstant()
              .isBefore(cutoff)) {
        return false;
      }
      if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
        try (FileChannel channel =
                FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock lock = channel.tryLock()) {
          if (EmptyChecks.isNull(lock)) {
            return false;
          }
        }
      }
      // 文件名由创建者随机生成且不复用；关闭句柄后删除以兼容 Windows。
      if (Files.deleteIfExists(path)) {
        Files.deleteIfExists(lockPath);
        return true;
      }
      return false;
    } catch (OverlappingFileLockException | NoSuchFileException unavailable) {
      return false;
    }
  }

  /** 在进程私有目录创建 owner-only 临时工作目录。 */
  public static Path createTempDirectory(String prefix) throws IOException {
    Path directory = privateDirectory();
    if (supportsPosix(directory)) {
      return Files.createTempDirectory(directory, prefix, OWNER_ONLY_DIRECTORY);
    }
    return Files.createTempDirectory(directory, prefix);
  }

  private static Path createTempFile(Path directory, String prefix, String suffix)
      throws IOException {
    if (supportsPosix(directory)) {
      return Files.createTempFile(directory, prefix, suffix, OWNER_ONLY_FILE);
    }
    return Files.createTempFile(directory, prefix, suffix);
  }

  private static void createOwnerOnlyFile(Path path) throws IOException {
    if (supportsPosix(path)) {
      Files.createFile(path, OWNER_ONLY_FILE);
      return;
    }
    Files.createFile(path);
  }

  private static Path privateDirectory() throws IOException {
    Path directory = resolveUnderTempRoot(ROOT_DIRECTORY);
    boolean posix = supportsPosix(directory);
    try {
      if (posix) {
        Files.createDirectory(directory, OWNER_ONLY_DIRECTORY);
      } else {
        Files.createDirectory(directory);
      }
    } catch (FileAlreadyExistsException alreadyExists) {
      // 已存在的目录在下方统一校验类型;POSIX 下再收敛一次权限。
    }
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("private temp path is not a directory: " + directory);
    }
    if (posix) {
      setOwnerOnlyPermissions(directory, true);
    }
    return directory;
  }

  /**
   * 目标文件系统是否支持 POSIX 权限视图。
   *
   * <p>创建前先判定,而不是"先按默认权限建出来再 chmod":前者不会留下创建到收紧之间对本机其他用户可读的窗口
   * (CodeQL java/local-temp-file-or-directory-information-disclosure 的推荐形态)。
   * 必须查询目标挂载点的 FileStore,不能用 provider 声明代替实际存储能力。非 POSIX 挂载点由部署方
   * 保证临时根的 ACL 仅允许运行账户访问,尤其不能把 Windows 自定义临时根视为天然私有。
   */
  static boolean supportsPosix(Path path) throws IOException {
    Path existing = path.toAbsolutePath();
    while (EmptyChecks.isNotNull(existing)) {
      try {
        return Files.getFileStore(existing).supportsFileAttributeView("posix");
      } catch (NoSuchFileException missing) {
        // 文件尚未创建时沿父目录找到实际挂载点;权限或存储故障不能当成不支持而降级。
        existing = existing.getParent();
      }
    }
    throw new IOException("cannot resolve temp file store: " + path);
  }

  /**
   * 收紧到 owner-only 权限。
   *
   * <p>只在 {@link #supportsPosix} 为真的路径上调用(创建时已带显式属性,这里用于既存目录的权限收敛),因此不再
   * 吞 {@code UnsupportedOperationException}:文件系统自称支持 posix 却拒绝设置权限属异常情况,应显式暴露。
   */
  private static void setOwnerOnlyPermissions(Path path, boolean directory) throws IOException {
    Files.setPosixFilePermissions(
        path,
        directory
            ? Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE)
            : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
  }
}
