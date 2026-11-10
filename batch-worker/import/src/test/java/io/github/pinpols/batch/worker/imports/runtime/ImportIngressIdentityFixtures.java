package io.github.pinpols.batch.worker.imports.runtime;

import io.github.pinpols.batch.common.config.FilesystemStorageProperties;
import io.github.pinpols.batch.common.config.KafkaNamespaceProperties;
import io.github.pinpols.batch.common.config.StorageBackendProperties;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipIdentity;
import java.nio.file.Path;

/** 入站扫描测试使用与实际存储配置一致的身份。 */
final class ImportIngressIdentityFixtures {
  private ImportIngressIdentityFixtures() {}

  static ImportIngressOwnershipIdentity s3Identity() {
    return new ImportIngressOwnershipIdentity(
        new KafkaNamespaceProperties(),
        new StorageBackendProperties(),
        new FilesystemStorageProperties());
  }

  static ImportIngressOwnershipIdentity filesystemIdentity(Path root) {
    StorageBackendProperties backend = new StorageBackendProperties();
    backend.setBackend("filesystem");
    FilesystemStorageProperties filesystem = new FilesystemStorageProperties();
    filesystem.setRoot(root.toString());
    return new ImportIngressOwnershipIdentity(new KafkaNamespaceProperties(), backend, filesystem);
  }
}
