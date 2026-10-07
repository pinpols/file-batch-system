package io.github.pinpols.batch.common.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** 私有落盘的创建边界: POSIX 权限在创建时生效,非 POSIX 仅允许继承已验证的私有 ACL。 */
public final class OwnerOnlyFiles {

  private OwnerOnlyFiles() {}

  public static Path createDirectories(Path path) throws IOException {
    Path created = Files.createDirectories(path, attributes(path, true));
    protectExisting(created, true);
    return created;
  }

  public static Path createFile(Path path) throws IOException {
    Path created = Files.createFile(path, attributes(path, false));
    protectExisting(created, false);
    return created;
  }

  public static void protectExisting(Path path, boolean directory) throws IOException {
    if (directory
        ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("private path has unexpected type: " + path);
    }
    if (supportsPosix(path)) {
      Files.setPosixFilePermissions(
          path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    } else {
      requirePrivateAcl(path);
    }
  }

  // JDK 创建 API 接收异构 FileAttribute 数组,其值类型由具体文件系统属性决定。
  @SuppressWarnings("java:S1452")
  public static FileAttribute<?>[] attributes(Path path, boolean directory) throws IOException {
    if (supportsPosix(path)) {
      return new FileAttribute<?>[] {
        PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))
      };
    }
    // 不按操作系统名称猜测安全性: 自定义临时根和网络挂载必须验证实际继承权限。
    requirePrivateAcl(existingAncestor(path));
    return new FileAttribute<?>[0];
  }

  private static boolean supportsPosix(Path path) throws IOException {
    return Files.getFileStore(existingAncestor(path)).supportsFileAttributeView("posix");
  }

  private static Path existingAncestor(Path path) throws IOException {
    Path current = path.toAbsolutePath();
    while (current != null) { // empty-check: allow - 遍历路径父级直到根目录。
      try {
        Files.readAttributes(current, "basic:isDirectory", LinkOption.NOFOLLOW_LINKS);
        return current;
      } catch (NoSuchFileException missing) {
        current = current.getParent();
      }
    }
    throw new IOException("cannot resolve private path filesystem: " + path);
  }

  private static void requirePrivateAcl(Path path) throws IOException {
    AclFileAttributeView view =
        Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (view == null) { // empty-check: allow - 不支持 POSIX 或 ACL 时禁止默认权限降级。
      throw new IOException("private path requires POSIX permissions or owner-only ACL: " + path);
    }
    Set<AclEntryPermission> sensitive = Set.of(
        AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA,
        AclEntryPermission.APPEND_DATA, AclEntryPermission.EXECUTE,
        AclEntryPermission.DELETE, AclEntryPermission.DELETE_CHILD,
        AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER);
    for (AclEntry entry : view.getAcl()) {
      if (entry.type() == AclEntryType.ALLOW
          && !entry.principal().equals(view.getOwner())
          && entry.permissions().stream().anyMatch(sensitive::contains)) {
        throw new IOException("private path ACL grants access to another principal: " + path);
      }
    }
  }
}
