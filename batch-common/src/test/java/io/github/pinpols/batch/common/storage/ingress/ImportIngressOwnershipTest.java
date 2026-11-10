package io.github.pinpols.batch.common.storage.ingress;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.FilesystemStorageProperties;
import io.github.pinpols.batch.common.config.KafkaNamespaceProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.config.StorageBackendProperties;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipRepository.Binding;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipRepository.Scope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("入站归属身份与对象保护范围")
class ImportIngressOwnershipTest {

  @Test
  @DisplayName("存储与扫描范围身份保持已有编码结果")
  void identity_shouldPreserveStorageAndScopeEncoding() {
    KafkaNamespaceProperties kafka = new KafkaNamespaceProperties();
    kafka.setNamespace(" prod ");
    StorageBackendProperties backend = new StorageBackendProperties();
    backend.setBackend(" S3 ");
    ImportIngressOwnershipIdentity identity =
        new ImportIngressOwnershipIdentity(kafka, backend, new FilesystemStorageProperties());
    S3StorageProperties storage = new S3StorageProperties();
    storage.setEndpoint("http://minio:9000/");
    storage.setBucket("batch-test");
    ObjectMapper objectMapper = new ObjectMapper();

    assertThat(identity.storageId(storage, objectMapper))
        .isEqualTo("72bb968aa9645ac8b2eab5f4482750af13add07c70e36f70ea1490ee844b0cad");
    assertThat(ImportIngressOwnershipIdentity.scopeId("abc", "ingress/acme/", objectMapper))
        .isEqualTo("f4198c758c9d301f6f61b68f5769b4b6f0bf4cfcb6cf2b65c8c689196dba813d");
  }

  @Test
  @DisplayName("文件系统配置绑定后仍保持既有存储身份")
  void identity_shouldPreserveFilesystemEncoding_whenUsingBoundProperties() {
    KafkaNamespaceProperties kafka = new KafkaNamespaceProperties();
    kafka.setNamespace(" prod ");
    StorageBackendProperties backend = new StorageBackendProperties();
    backend.setBackend(" FILESYSTEM ");
    FilesystemStorageProperties filesystem = new FilesystemStorageProperties();
    filesystem.setRoot("/batch/ingress");
    ImportIngressOwnershipIdentity identity =
        new ImportIngressOwnershipIdentity(kafka, backend, filesystem);
    S3StorageProperties storage = new S3StorageProperties();
    storage.setBucket("batch-test");

    assertThat(identity.storageId(storage, new ObjectMapper()))
        .isEqualTo("5c5510fbf82dfbdfd5bec3170020bc7109b31a50129eee14e379f1a9c6a95ea0");
  }

  @Test
  @DisplayName("扫描目录及目录外的指定日期清单受到归属保护")
  void scope_shouldProtectPrefixAndExactDatedManifest() {
    Scope scope = new Scope(
        "scope",
        "storage",
        "bucket-a",
        "ingress/a/",
        "RETIRED",
        List.of(
            new Binding(
                "MANIFEST",
                "tenant-a",
                "ingress/a/",
                "group-a",
                "manifests/a-{bizDateCompact}.batch.json",
                null,
                true),
            new Binding(
                "MANIFEST",
                "tenant-a",
                "ingress/a/other/",
                "group-b",
                "manifests/b-{bizDate}.batch.json",
                null,
                false),
            new Binding("CHANNEL", "tenant-a", "ingress/a/", null, null, "api-a", true)));

    assertThat(scope.protects("bucket-a", "ingress/a/input.csv")).isTrue();
    assertThat(scope.protects("bucket-a", "manifests/a-20261109.batch.json")).isTrue();
    assertThat(scope.protects("bucket-a", "manifests/b-2026-11-09.batch.json")).isTrue();
    assertThat(scope.protects("bucket-a", "manifests/a-20261109.batch.json.bak"))
        .isFalse();
    assertThat(scope.protects("bucket-a", "manifests/a-2026-11-09.batch.json")).isFalse();
    assertThat(scope.protects("bucket-a", "ordinary/file.csv")).isFalse();
    assertThat(scope.protects("bucket-b", "ingress/a/input.csv")).isFalse();
  }
}
