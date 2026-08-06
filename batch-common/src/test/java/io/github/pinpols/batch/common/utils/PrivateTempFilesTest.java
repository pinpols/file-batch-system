package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import org.junit.jupiter.api.Test;

class PrivateTempFilesTest {

  @Test
  void createsFileAndDirectoryUnderPrivateRoot() throws Exception {
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
  void rejectsPrivateRootWhenTempPathContainsARegularFile() throws Exception {
    Path tempRoot = Files.createTempDirectory("private-temp-test-");
    Path rootAsFile = tempRoot.resolve("file-batch-private");
    Files.createFile(rootAsFile);
    String original = System.getProperty("java.io.tmpdir");
    System.setProperty("java.io.tmpdir", tempRoot.toString());
    try {
      assertThatThrownBy(() -> PrivateTempFiles.createTempFile("test-", ".tmp"))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("not a directory");
    } finally {
      if (original == null) {
        System.clearProperty("java.io.tmpdir");
      } else {
        System.setProperty("java.io.tmpdir", original);
      }
      Files.deleteIfExists(rootAsFile);
      Files.deleteIfExists(tempRoot);
    }
  }

  @Test
  void createsRootWhenTempPathIsFresh() throws Exception {
    Path tempRoot = Files.createTempDirectory("private-temp-fresh-");
    String original = System.getProperty("java.io.tmpdir");
    System.setProperty("java.io.tmpdir", tempRoot.toString());
    Path file = null;
    Path directory = null;
    try {
      file = PrivateTempFiles.createTempFile("fresh-", ".tmp");
      directory = PrivateTempFiles.createTempDirectory("fresh-");

      assertThat(file).exists();
      assertThat(directory).isDirectory();
    } finally {
      if (original == null) {
        System.clearProperty("java.io.tmpdir");
      } else {
        System.setProperty("java.io.tmpdir", original);
      }
      Files.deleteIfExists(file);
      Files.deleteIfExists(directory);
      Files.deleteIfExists(tempRoot.resolve("file-batch-private"));
      Files.deleteIfExists(tempRoot);
    }
  }
}
