package io.github.pinpols.batch.common.utils;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
