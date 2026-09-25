package io.github.pinpols.batch.testing;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/** 测试对象存储端点，既可以来自 Testcontainers，也可以指向外部 S3 兼容服务。 */
public interface TestObjectStoreEndpoint {

  String getEndpoint();

  String getAccessKey();

  String getSecretKey();

  String getDefaultBucket();

  default String getRegion() {
    return "us-east-1";
  }

  default boolean isPathStyleEnabled() {
    return true;
  }

  default S3Client client() {
    return S3Client.builder()
        .endpointOverride(URI.create(getEndpoint()))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(getAccessKey(), getSecretKey())))
        .forcePathStyle(isPathStyleEnabled())
        .region(Region.of(getRegion()))
        .build();
  }

  default void ensureBucketExists(String bucketName) {
    InstantRetrySupport.retryUntil(
        Duration.ofMinutes(2), "failed to ensure S3-compatible test bucket: " + bucketName, () -> {
          try (S3Client client = client()) {
            if (!bucketExists(client, bucketName)) {
              client.createBucket(
                  CreateBucketRequest.builder().bucket(bucketName).build());
            }
          }
        });
  }

  private static boolean bucketExists(S3Client client, String bucketName) {
    try {
      client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
      return true;
    } catch (NoSuchBucketException notFound) {
      return false;
    }
  }

  final class InstantRetrySupport {

    private InstantRetrySupport() {}

    static void retryUntil(Duration timeout, String message, CheckedRunnable action) {
      Instant deadline = BatchDateTimeSupport.utcNow().plus(timeout);
      Exception lastFailure = null;
      while (BatchDateTimeSupport.utcNow().isBefore(deadline)) {
        try {
          action.run();
          return;
        } catch (Exception exception) {
          lastFailure = exception;
          sleepBeforeRetry();
        }
      }
      throw new IllegalStateException(message, lastFailure);
    }

    private static void sleepBeforeRetry() {
      try {
        TimeUnit.MILLISECONDS.sleep(500);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "interrupted while waiting for S3-compatible object store readiness", interrupted);
      }
    }
  }

  @FunctionalInterface
  interface CheckedRunnable {

    void run() throws ObjectStoreEndpointException;
  }

  final class ObjectStoreEndpointException extends Exception {

    ObjectStoreEndpointException(String message, Throwable cause) {
      super(message, cause);
    }

    ObjectStoreEndpointException(String message) {
      super(message);
    }
  }
}
