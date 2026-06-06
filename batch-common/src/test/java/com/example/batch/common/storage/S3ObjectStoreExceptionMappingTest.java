package com.example.batch.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.S3StorageProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** 不依赖容器的轻量单测：验证 AWS SDK v2 {@link S3Exception} 各 errorCode 到统一异常体系的映射。 */
@ExtendWith(MockitoExtension.class)
class S3ObjectStoreExceptionMappingTest {

  @Mock private S3Client s3Client;
  @Mock private S3Presigner presigner;

  private S3ObjectStore store;

  @BeforeEach
  void setUp() {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setBucket("bucket");
    store = new S3ObjectStore(s3Client, presigner, properties);
  }

  @Test
  void shouldMapNoSuchKeyToObjectNotFound() {
    stubHeadThrows("NoSuchKey");
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectNotFoundException.class);
  }

  @Test
  void shouldMapEmptyErrorCode404ToObjectNotFound() {
    // 真实 HEAD 响应无 body → SDK 拿不到 errorCode，仅有 statusCode 404；必须仍判定为对象不存在。
    S3Exception ex =
        (S3Exception)
            S3Exception.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("").build())
                .message("Not Found")
                .statusCode(404)
                .build();
    when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(ex);
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectNotFoundException.class);
  }

  @Test
  void shouldMapAccessDeniedToAccessException() {
    stubHeadThrows("AccessDenied");
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectStoreAccessException.class);
  }

  @Test
  void shouldMapSignatureMismatchToAccessException() {
    stubHeadThrows("SignatureDoesNotMatch");
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectStoreAccessException.class);
  }

  @Test
  void shouldMapUnknownCodeToBaseException() {
    stubHeadThrows("InternalError");
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectStoreException.class)
        .isNotInstanceOf(ObjectNotFoundException.class)
        .isNotInstanceOf(ObjectStoreAccessException.class);
  }

  @Test
  void shouldMapNonS3ExceptionToBaseException() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(new UncheckedIOException(new IOException("socket reset")));
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectStoreException.class)
        .isNotInstanceOf(ObjectNotFoundException.class)
        .isNotInstanceOf(ObjectStoreAccessException.class);
  }

  @Test
  void existsShouldReturnFalseOnNoSuchKey() {
    stubHeadThrows("NoSuchKey");
    assertThat(store.exists("bucket", "key")).isFalse();
  }

  @Test
  void existsShouldThrowAccessExceptionOnAccessDenied() {
    stubHeadThrows("AccessDenied");
    assertThatThrownBy(() -> store.exists("bucket", "key"))
        .isInstanceOf(ObjectStoreAccessException.class);
  }

  private void stubHeadThrows(String code) {
    S3Exception ex =
        (S3Exception)
            S3Exception.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
                .message("msg")
                .statusCode(code.equals("NoSuchKey") ? 404 : 403)
                .build();
    when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(ex);
  }
}
