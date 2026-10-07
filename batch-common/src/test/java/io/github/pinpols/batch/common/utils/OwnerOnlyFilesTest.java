package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("私有文件创建:创建时权限,既存路径与不支持权限的挂载点")
class OwnerOnlyFilesTest {

  @Test
  @DisplayName("嵌套目录与文件创建时仅属主可访问,既存宽松目录收紧权限")
  void shouldCreatePrivatePaths_whenUsingPosixFilesystem(@TempDir Path root) throws Exception {
    Path directory = OwnerOnlyFiles.createDirectories(root.resolve("nested/data"));
    Path file = OwnerOnlyFiles.createFile(directory.resolve("payload"));
    assertThat(Files.getPosixFilePermissions(directory))
        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    assertThat(Files.getPosixFilePermissions(directory.getParent()))
        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    assertThat(Files.getPosixFilePermissions(file))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
    OwnerOnlyFiles.createDirectories(directory);
    assertThat(Files.getPosixFilePermissions(directory))
        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
  }

  @Test
  @DisplayName("私有目录路径被符号链接占用时拒绝,不修改链接目标权限")
  void shouldRejectSymlink_whenPrivateDirectoryIsRedirected(@TempDir Path root) throws Exception {
    Path target = Files.createDirectory(root.resolve("target"));
    var original = Files.getPosixFilePermissions(target);
    Path link = Files.createSymbolicLink(root.resolve("link"), target);
    assertThatThrownBy(() -> OwnerOnlyFiles.createDirectories(link))
        .isInstanceOf(IOException.class);
    assertThat(Files.getPosixFilePermissions(target)).isEqualTo(original);
    assertThatThrownBy(() -> OwnerOnlyFiles.protectExisting(link, false))
        .isInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("挂载点不支持 POSIX 或 ACL 时显式失败,不创建默认权限文件")
  void shouldRejectUnsupportedFilesystem_whenNoPrivatePermissionsExist(@TempDir Path root)
      throws Exception {
    Path archive = root.resolve("store.zip");
    try (var zip = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
      Path path = zip.getPath("/secret");
      assertThatThrownBy(() -> OwnerOnlyFiles.createFile(path))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("owner-only ACL");
      assertThat(Files.exists(path)).isFalse();
    }
  }
}
