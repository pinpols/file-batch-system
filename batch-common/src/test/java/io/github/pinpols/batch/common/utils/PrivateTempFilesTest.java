package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("私有临时文件工具: 私有根目录下的文件与目录创建, 权限收紧及异常路径")
class PrivateTempFilesTest {

  @Test
  @DisplayName("私有根目录: 文件与目录均落在私有根下, 仅属主可读写, 目录可执行")
  void shouldCreateFileAndDirectoryWithOwnerOnlyPermissions_whenPrivateRootIsUsed()
      throws Exception {
    Path file = PrivateTempFiles.createTempFile("test-", ".tmp");
    Path directory = PrivateTempFiles.createTempDirectory("test-");
    try {
      assertThat(file.getParent().getFileName()).hasToString("file-batch-private");
      assertThat(Files.isRegularFile(file)).isTrue();
      assertThat(Files.isDirectory(directory)).isTrue();
      if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
        assertThat(Files.getPosixFilePermissions(file))
            .containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(Files.getPosixFilePermissions(directory))
            .containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
      }
    } finally {
      Files.deleteIfExists(file);
      Files.deleteIfExists(directory);
    }
  }

  @Test
  @DisplayName("活跃临时副本持有锁时清理器不删除,关闭句柄后文件与锁均移除")
  void shouldPreserveActiveFileAndCleanOnClose_whenFileIsLocked() throws Exception {
    Path path;
    Path lockPath;
    Path lockNamedPayload;
    try (PrivateTempFiles.LockedTempFile file =
            PrivateTempFiles.createLockedTempFile("lock-review-", ".tmp");
        PrivateTempFiles.LockedTempFile lockSuffixFile =
            PrivateTempFiles.createLockedTempFile("lock-review-", ".lock")) {
      path = file.path();
      lockPath = file.lockPath();
      lockNamedPayload = lockSuffixFile.path();
      assertThat(PrivateTempFiles.deleteStaleUnlockedFiles(
              "lock-review-", Instant.now().plusSeconds(60)))
          .isZero();
      assertThat(path).exists();
      assertThat(lockPath).exists();
      assertThat(lockNamedPayload).exists();
    }
    assertThat(path).doesNotExist();
    assertThat(lockPath).doesNotExist();
    assertThat(lockNamedPayload).doesNotExist();
  }

  @Test
  @DisplayName("释放临时文件锁后保留取证文件,允许过期清理,重复关闭不报错")
  void shouldRetainFileUntilStaleCleanup_whenLockIsReleased() throws Exception {
    try (PrivateTempFiles.LockedTempFile file =
        PrivateTempFiles.createLockedTempFile("lock-retain-review-", ".tmp")) {
      file.releaseLock();

      assertThat(file.path()).exists();
      assertThat(file.lockPath()).doesNotExist();
      assertThat(PrivateTempFiles.deleteStaleUnlockedFiles(
              "lock-retain-review-", Instant.now().plusSeconds(60)))
          .isEqualTo(1);
      assertThat(file.path()).doesNotExist();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("其他进程持锁时保留超期数据或孤立锁文件,强杀后允许清理")
  void shouldCleanOnlyAfterOwnerProcessDies_whenStaleFileIsLocked(
      boolean payloadAlreadyDeleted, @TempDir Path tempDirectory) throws Exception {
    String originalTempRoot = System.getProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY);
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Process owner = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-D" + PrivateTempFiles.TEMP_ROOT_PROPERTY + "=" + tempDirectory,
            "-cp",
            classpath,
            TempFileLockOwner.class.getName())
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
    System.setProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY, tempDirectory.toString());
    try {
      Path file = awaitOwnedFile(tempDirectory, owner);
      Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
      Instant cutoff = Instant.now();
      Files.setLastModifiedTime(file, FileTime.from(cutoff.minusSeconds(60)));
      Files.setLastModifiedTime(lockFile, FileTime.from(cutoff.minusSeconds(60)));
      if (payloadAlreadyDeleted) {
        Files.delete(file);
      }

      assertThat(PrivateTempFiles.deleteStaleUnlockedFiles("lock-crash-review-", cutoff))
          .isZero();
      assertThat(lockFile).exists();
      if (!payloadAlreadyDeleted) {
        assertThat(file).exists();
      }

      owner.destroyForcibly();
      assertThat(owner.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(PrivateTempFiles.deleteStaleUnlockedFiles("lock-crash-review-", cutoff))
          .isEqualTo(1);
      try (var remaining = Files.list(file.getParent())) {
        assertThat(remaining).isEmpty();
      }
    } finally {
      try {
        owner.destroyForcibly();
        owner.waitFor(10, TimeUnit.SECONDS);
      } finally {
        if (originalTempRoot == null) {
          System.clearProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY);
        } else {
          System.setProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY, originalTempRoot);
        }
      }
    }
  }

  private static Path awaitOwnedFile(Path tempDirectory, Process owner) throws Exception {
    Path privateRoot = tempDirectory.resolve("file-batch-private");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (Files.isDirectory(privateRoot)) {
        try (var files = Files.list(privateRoot)) {
          var payload = files
              .filter(path -> path.getFileName().toString().startsWith("lock-crash-review-"))
              .filter(path -> !path.getFileName().toString().endsWith(".lock"))
              .findFirst();
          if (payload.isPresent()) {
            return payload.get();
          }
        }
      }
      if (!owner.isAlive()) {
        throw new IllegalStateException("lock owner process exited before creating its file");
      }
      Thread.sleep(20);
    }
    throw new IllegalStateException("timed out waiting for lock owner file");
  }

  /** 独立 JVM 持有文件锁,父测试用强杀模拟 Worker 退出,不执行 close。 */
  public static final class TempFileLockOwner {

    private TempFileLockOwner() {}

    public static void main(String[] args) throws Exception {
      try (var file = PrivateTempFiles.createLockedTempFile("lock-crash-review-", ".tmp")) {
        System.in.read();
      }
    }
  }

  @Test
  @DisplayName("私有根路径被普通文件占用: 创建临时文件抛出 IO 异常")
  void shouldReject_whenPrivateRootPathPointsToARegularFile() throws Exception {
    Path tempRoot = Files.createTempDirectory("private-temp-test-");
    Path rootAsFile = tempRoot.resolve("file-batch-private");
    Files.createFile(rootAsFile);
    String original = System.getProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY);
    System.setProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY, tempRoot.toString());
    try {
      assertThatThrownBy(() -> PrivateTempFiles.createTempFile("test-", ".tmp"))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("not a directory");
    } finally {
      if (original == null) {
        System.clearProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY);
      } else {
        System.setProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY, original);
      }
      Files.deleteIfExists(rootAsFile);
      Files.deleteIfExists(tempRoot);
    }
  }

  @Test
  @DisplayName("临时路径尚无私有根: 自动创建根目录并成功创建文件与目录")
  void shouldCreatePrivateRoot_whenTempPathHasNoRootYet() throws Exception {
    Path tempRoot = Files.createTempDirectory("private-temp-fresh-");
    String original = System.getProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY);
    System.setProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY, tempRoot.toString());
    Path file = null;
    Path directory = null;
    try {
      file = PrivateTempFiles.createTempFile("fresh-", ".tmp");
      directory = PrivateTempFiles.createTempDirectory("fresh-");

      assertThat(file).exists();
      assertThat(directory).isDirectory();
    } finally {
      if (original == null) {
        System.clearProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY);
      } else {
        System.setProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY, original);
      }
      Files.deleteIfExists(file);
      Files.deleteIfExists(directory);
      Files.deleteIfExists(tempRoot.resolve("file-batch-private"));
      Files.deleteIfExists(tempRoot);
    }
  }
}
