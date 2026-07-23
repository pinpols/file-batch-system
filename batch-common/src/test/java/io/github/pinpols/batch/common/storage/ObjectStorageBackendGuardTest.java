package io.github.pinpols.batch.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.config.FilesystemStorageProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.config.StorageBackendGuardProperties;
import io.github.pinpols.batch.common.stateful.StatefulBackendGuard;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ObjectStorageBackendGuardTest {

  @Test
  void includesS3EndpointRegionAndBucketInIdentity() {
    S3StorageProperties s3 = s3();
    MockEnvironment environment = environment().withProperty("batch.storage.backend", "s3");

    StatefulBackendGuard.DesiredBackend desired =
        guard(s3, filesystem(), environment).desiredBackend();

    assertThat(desired.backend()).isEqualTo("s3");
    assertThat(desired.backendIdentity())
        .isEqualTo("endpoint=http://minio:9000|region=us-east-1|bucket=batch-prod");
  }

  @Test
  void includesAbsoluteFilesystemRootAndBucketInIdentity() {
    FilesystemStorageProperties filesystem = filesystem();
    filesystem.setRoot("./target/object-store");
    MockEnvironment environment = environment().withProperty("batch.storage.backend", "filesystem");

    StatefulBackendGuard.DesiredBackend desired =
        guard(s3(), filesystem, environment).desiredBackend();

    assertThat(desired.backend()).isEqualTo("filesystem");
    assertThat(desired.backendIdentity())
        .isEqualTo(
            "root="
                + Path.of("./target/object-store").toAbsolutePath().normalize()
                + "|bucket=batch-prod");
  }

  @Test
  void rejectsUnknownStorageBackend() {
    MockEnvironment environment = environment().withProperty("batch.storage.backend", "local");

    assertThatThrownBy(() -> guard(s3(), filesystem(), environment).desiredBackend())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unsupported batch.storage.backend");
  }

  private ObjectStorageBackendGuard guard(
      S3StorageProperties s3, FilesystemStorageProperties filesystem, MockEnvironment environment) {
    return new ObjectStorageBackendGuard(
        mock(DataSource.class), s3, filesystem, new StorageBackendGuardProperties(), environment);
  }

  private S3StorageProperties s3() {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setEndpoint("http://minio:9000");
    properties.setRegion("us-east-1");
    properties.setBucket("batch-prod");
    return properties;
  }

  private FilesystemStorageProperties filesystem() {
    return new FilesystemStorageProperties();
  }

  private MockEnvironment environment() {
    return new MockEnvironment().withProperty("spring.application.name", "batch-orchestrator");
  }
}
