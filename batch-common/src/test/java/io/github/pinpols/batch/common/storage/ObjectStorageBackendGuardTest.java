package io.github.pinpols.batch.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.config.ApplicationNameProvider;
import io.github.pinpols.batch.common.config.FilesystemStorageProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.config.StorageBackendGuardProperties;
import io.github.pinpols.batch.common.config.StorageBackendProperties;
import io.github.pinpols.batch.common.stateful.StatefulBackendGuard;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("对象存储后端守护:验证各后端身份指纹的组成要素以及未支持后端的拒绝")
class ObjectStorageBackendGuardTest {

  @Test
  @DisplayName("选择远端对象存储后端时,身份指纹包含接入地址、区域与桶名")
  void shouldIncludeEndpointRegionAndBucket_whenBackendIsRemote() {
    S3StorageProperties s3 = s3();
    MockEnvironment environment =
        environment().withProperty(StorageBackendProperties.BACKEND_KEY, ObjectStorageBackends.S3);

    StatefulBackendGuard.DesiredBackend desired =
        guard(s3, filesystem(), environment).desiredBackend();

    assertThat(desired.backend()).isEqualTo(ObjectStorageBackends.S3);
    assertThat(desired.backendIdentity())
        .isEqualTo("endpoint=http://minio:9000|region=us-east-1|bucket=batch-prod");
  }

  @Test
  @DisplayName("选择本地文件后端时,身份指纹包含规范化后的绝对根路径与桶名")
  void shouldIncludeAbsoluteRootAndBucket_whenBackendIsFilesystem() {
    FilesystemStorageProperties filesystem = filesystem();
    filesystem.setRoot("./target/object-store");
    MockEnvironment environment = environment()
        .withProperty(StorageBackendProperties.BACKEND_KEY, ObjectStorageBackends.FILESYSTEM);

    StatefulBackendGuard.DesiredBackend desired =
        guard(s3(), filesystem, environment).desiredBackend();

    assertThat(desired.backend()).isEqualTo(ObjectStorageBackends.FILESYSTEM);
    assertThat(desired.backendIdentity())
        .isEqualTo("root="
            + Path.of("./target/object-store").toAbsolutePath().normalize()
            + "|bucket=batch-prod");
  }

  @Test
  @DisplayName("配置了未支持的后端取值时,解析期望后端即抛出异常并提示不受支持")
  void shouldReject_whenBackendValueUnsupported() {
    MockEnvironment environment =
        environment().withProperty(StorageBackendProperties.BACKEND_KEY, "local");

    assertThatThrownBy(() -> guard(s3(), filesystem(), environment).desiredBackend())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unsupported batch.storage.backend");
  }

  private ObjectStorageBackendGuard guard(
      S3StorageProperties s3, FilesystemStorageProperties filesystem, MockEnvironment environment) {
    StorageBackendProperties backendProperties = Binder.get(environment)
        .bind("batch.storage", Bindable.of(StorageBackendProperties.class))
        .orElseGet(StorageBackendProperties::new);
    return new ObjectStorageBackendGuard(
        mock(DataSource.class),
        s3,
        filesystem,
        new StorageBackendGuardProperties(),
        backendProperties,
        environment);
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
    return new MockEnvironment()
        .withProperty(ApplicationNameProvider.APPLICATION_NAME_KEY, "batch-orchestrator");
  }
}
