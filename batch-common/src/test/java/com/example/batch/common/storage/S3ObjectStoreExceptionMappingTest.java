package com.example.batch.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.S3StorageProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
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
    properties.setAutoCreateBucket(false);
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

  @Test
  void putLargeObjectShouldUseMultipart() {
    S3StorageProperties properties = multipartProperties();
    store = new S3ObjectStore(s3Client, presigner, properties);
    when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
        .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
    when(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
        .thenReturn(UploadPartResponse.builder().eTag("etag").build());

    byte[] data = new byte[6 * 1024 * 1024];
    store.put("bucket", "large.csv", new ByteArrayInputStream(data), data.length, "text/csv");

    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    verify(s3Client).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    verify(s3Client, times(2)).uploadPart(any(UploadPartRequest.class), any(RequestBody.class));
    verify(s3Client).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
  }

  @Test
  void putMultipartShouldAbortOnUploadFailure() {
    S3StorageProperties properties = multipartProperties();
    store = new S3ObjectStore(s3Client, presigner, properties);
    when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
        .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
    when(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
        .thenThrow(new RuntimeException("boom"));

    byte[] data = new byte[6 * 1024 * 1024];
    assertThatThrownBy(
            () ->
                store.put(
                    "bucket", "large.csv", new ByteArrayInputStream(data), data.length, "text/csv"))
        .isInstanceOf(ObjectStoreException.class);

    verify(s3Client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    verify(s3Client, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
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

  private static S3StorageProperties multipartProperties() {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setBucket("bucket");
    properties.setAutoCreateBucket(false);
    properties.setMultipartEnabled(true);
    properties.setMultipartThresholdBytes(5L * 1024 * 1024);
    properties.setMultipartPartSizeBytes(5 * 1024 * 1024);
    return properties;
  }
}
