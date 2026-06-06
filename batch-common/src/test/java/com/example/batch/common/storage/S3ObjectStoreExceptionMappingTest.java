package com.example.batch.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.S3StorageProperties;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 不依赖容器的轻量单测：验证 MinIO {@link ErrorResponseException} 各 code 到统一异常体系的映射。 */
@ExtendWith(MockitoExtension.class)
class S3ObjectStoreExceptionMappingTest {

  @Mock private MinioClient minioClient;

  private S3ObjectStore store;

  @BeforeEach
  void setUp() {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setBucket("bucket");
    store = new S3ObjectStore(minioClient, properties);
  }

  @Test
  void shouldMapNoSuchKeyToObjectNotFound() throws Exception {
    stubStatThrows("NoSuchKey");
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectNotFoundException.class);
  }

  @Test
  void shouldMapAccessDeniedToAccessException() throws Exception {
    stubStatThrows("AccessDenied");
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectStoreAccessException.class);
  }

  @Test
  void shouldMapSignatureMismatchToAccessException() throws Exception {
    stubStatThrows("SignatureDoesNotMatch");
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectStoreAccessException.class);
  }

  @Test
  void shouldMapUnknownCodeToBaseException() throws Exception {
    stubStatThrows("InternalError");
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectStoreException.class)
        .isNotInstanceOf(ObjectNotFoundException.class)
        .isNotInstanceOf(ObjectStoreAccessException.class);
  }

  @Test
  void shouldMapNonErrorResponseToBaseException() throws Exception {
    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenThrow(new IOException("socket reset"));
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectStoreException.class)
        .isNotInstanceOf(ObjectNotFoundException.class)
        .isNotInstanceOf(ObjectStoreAccessException.class);
  }

  @Test
  void existsShouldReturnFalseOnNoSuchKey() throws Exception {
    stubStatThrows("NoSuchKey");
    assertThat(store.exists("bucket", "key")).isFalse();
  }

  @Test
  void existsShouldThrowAccessExceptionOnAccessDenied() throws Exception {
    stubStatThrows("AccessDenied");
    assertThatThrownBy(() -> store.exists("bucket", "key"))
        .isInstanceOf(ObjectStoreAccessException.class);
  }

  private void stubStatThrows(String code) throws Exception {
    ErrorResponse errorResponse =
        new ErrorResponse(code, "msg", "bucket", "key", "key", "rid", "hid");
    ErrorResponseException ex = new ErrorResponseException(errorResponse, null, "http trace");
    when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(ex);
  }
}
