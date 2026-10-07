package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    try (PrivateTempFiles.LockedTempFile file =
        PrivateTempFiles.createLockedTempFile("lock-review-", ".tmp")) {
      path = file.path();
      lockPath = file.lockPath();
      assertThat(PrivateTempFiles.deleteStaleUnlockedFiles(
              "lock-review-", Instant.now().plusSeconds(60)))
          .isZero();
      assertThat(path).exists();
      assertThat(lockPath).exists();
    }
    assertThat(path).doesNotExist();
    assertThat(lockPath).doesNotExist();
  }

  @Test
  @DisplayName("目标挂载点不支持 POSIX 时不采用 provider 的全局能力声明")
  void shouldUseActualFileStore_whenPosixSupportDiffersByMount() throws Exception {
    Path path = Path.of("mount-specific-temp").toAbsolutePath();
    FileStore store = mock(FileStore.class);
    when(store.supportsFileAttributeView("posix")).thenReturn(false);
    try (var files = mockStatic(Files.class)) {
      files.when(() -> Files.getFileStore(path)).thenReturn(store);
      assertThat(PrivateTempFiles.supportsPosix(path)).isFalse();
    }
  }

  @Test
  @DisplayName("目标尚未创建时查询最近已有父目录的挂载点,存储查询失败则显式报错")
  void shouldResolveParentStoreAndPropagateFailure_whenTargetDoesNotExist() throws Exception {
    Path path = Path.of("mount-specific-temp", "new-file").toAbsolutePath();
    FileStore store = mock(FileStore.class);
    when(store.supportsFileAttributeView("posix")).thenReturn(true);
    try (var files = mockStatic(Files.class)) {
      files
          .when(() -> Files.getFileStore(path))
          .thenThrow(new NoSuchFileException(path.toString()));
      files.when(() -> Files.getFileStore(path.getParent())).thenReturn(store);
      assertThat(PrivateTempFiles.supportsPosix(path)).isTrue();
      files
          .when(() -> Files.getFileStore(path.getParent()))
          .thenThrow(new IOException("store unavailable"));
      assertThatThrownBy(() -> PrivateTempFiles.supportsPosix(path))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("store unavailable");
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
