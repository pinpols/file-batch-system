package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
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
      if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
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
