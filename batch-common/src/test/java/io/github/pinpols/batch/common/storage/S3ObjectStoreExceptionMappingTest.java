package io.github.pinpols.batch.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("远端对象存储异常映射:验证各类服务端错误码、空错误码与网络异常到统一异常体系的归类")
class S3ObjectStoreExceptionMappingTest {

  @Mock
  private S3Client s3Client;

  @Mock
  private S3Presigner presigner;

  private S3ObjectStore store;

  @BeforeEach
  void setUp() {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setBucket("bucket");
    properties.setAutoCreateBucket(false);
    store = new S3ObjectStore(s3Client, presigner, properties);
  }

  @Test
  @DisplayName("错误码指示键不存在时映射为对象未找到异常")
  void shouldMapNoSuchKeyToObjectNotFound() {
    stubHeadThrows("NoSuchKey");
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectNotFoundException.class);
  }

  @Test
  @DisplayName("无错误码但状态码为未找到时仍映射为对象未找到异常")
  void shouldMapEmptyErrorCode404ToObjectNotFound() {
    // 真实 HEAD 响应无 body → SDK 拿不到 errorCode，仅有 statusCode 404；必须仍判定为对象不存在。
    S3Exception ex = (S3Exception) S3Exception.builder()
        .awsErrorDetails(AwsErrorDetails.builder().errorCode("").build())
        .message("Not Found")
        .statusCode(404)
        .build();
    when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(ex);
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectNotFoundException.class);
  }

  @Test
  @DisplayName("错误码指示拒绝访问时映射为访问异常")
  void shouldMapAccessDeniedToAccessException() {
    stubHeadThrows("AccessDenied");
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectStoreAccessException.class);
  }

  @Test
  @DisplayName("错误码指示签名不匹配时映射为访问异常")
  void shouldMapSignatureMismatchToAccessException() {
    stubHeadThrows("SignatureDoesNotMatch");
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectStoreAccessException.class);
  }

  @Test
  @DisplayName("无法识别的错误码归入基础对象存储异常,不归入未找到或访问异常")
  void shouldMapUnknownCodeToBaseException() {
    stubHeadThrows("InternalError");
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectStoreException.class)
        .isNotInstanceOf(ObjectNotFoundException.class)
        .isNotInstanceOf(ObjectStoreAccessException.class);
  }

  @Test
  @DisplayName("非服务端异常归入基础对象存储异常,不归入未找到或访问异常")
  void shouldMapNonS3ExceptionToBaseException() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(new UncheckedIOException(new IOException("socket reset")));
    assertThatThrownBy(() -> store.statSize("bucket", "key"))
        .isInstanceOf(ObjectStoreException.class)
        .isNotInstanceOf(ObjectNotFoundException.class)
        .isNotInstanceOf(ObjectStoreAccessException.class);
  }

  @Test
  @DisplayName("键不存在时存在性判断返回不存在,不抛出异常")
  void shouldReportMissing_whenKeyDoesNotExist() {
    stubHeadThrows("NoSuchKey");
    assertThat(store.exists("bucket", "key")).isFalse();
  }

  @Test
  @DisplayName("存在性探测被拒绝访问时抛出访问异常")
  void shouldThrowAccessException_whenExistenceProbeDenied() {
    stubHeadThrows("AccessDenied");
    assertThatThrownBy(() -> store.exists("bucket", "key"))
        .isInstanceOf(ObjectStoreAccessException.class);
  }

  @Test
  @DisplayName("载荷超过阈值时改走分片上传,不再整块写入并正常收尾")
  void shouldSwitchToMultipart_whenPayloadExceedsThreshold() {
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
  @DisplayName("分片上传中途失败时中止分片并抛出基础异常,不做收尾提交")
  void shouldAbortMultipart_whenPartUploadFails() {
    S3StorageProperties properties = multipartProperties();
    store = new S3ObjectStore(s3Client, presigner, properties);
    when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
        .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
    when(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
        .thenThrow(new RuntimeException("boom"));

    byte[] data = new byte[6 * 1024 * 1024];
    assertThatThrownBy(() -> store.put(
            "bucket", "large.csv", new ByteArrayInputStream(data), data.length, "text/csv"))
        .isInstanceOf(ObjectStoreException.class);

    verify(s3Client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    verify(s3Client, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
  }

  private void stubHeadThrows(String code) {
    S3Exception ex = (S3Exception) S3Exception.builder()
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
