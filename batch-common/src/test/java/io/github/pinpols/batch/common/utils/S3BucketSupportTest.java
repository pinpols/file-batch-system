package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@DisplayName("S3 Bucket 初始化:托管后端跳过探测,自建后端兼容通用 404 并创建 Bucket")
class S3BucketSupportTest {

  @Test
  @DisplayName("关闭自动创建时不调用需要额外权限的 HeadBucket 和 CreateBucket")
  void shouldSkipBucketProbe_whenAutoCreateDisabled() {
    S3Client s3Client = mock(S3Client.class);

    boolean ready =
        S3BucketSupport.ensureBucket(s3Client, "managed-bucket", mock(Logger.class), "test", false);

    assertThat(ready).isTrue();
    verify(s3Client, never()).headBucket(any(HeadBucketRequest.class));
    verify(s3Client, never()).createBucket(any(CreateBucketRequest.class));
  }

  @Test
  @DisplayName("兼容后端用通用 S3 404 表示 Bucket 不存在时自动创建")
  void shouldCreateBucket_whenHeadBucketReturnsGenericNotFound() {
    S3Client s3Client = mock(S3Client.class);
    when(s3Client.headBucket(any(HeadBucketRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(404).message("missing").build());

    boolean ready =
        S3BucketSupport.ensureBucket(s3Client, "batch-data", mock(Logger.class), "test", true);

    assertThat(ready).isTrue();
    verify(s3Client)
        .createBucket(CreateBucketRequest.builder().bucket("batch-data").build());
  }
}
