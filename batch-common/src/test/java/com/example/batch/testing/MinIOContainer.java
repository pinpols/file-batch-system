package com.example.batch.testing;

import com.example.batch.common.time.BatchDateTimeSupport;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/** 集成测试使用的轻量 MinIO 测试容器封装。 */
public final class MinIOContainer extends GenericContainer<MinIOContainer> {

  private static final int MINIO_API_PORT = 9000;
  private static final int MINIO_CONSOLE_PORT = 9001;
  private static final DockerImageName IMAGE =
      DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z");

  private final String accessKey;
  private final String secretKey;
  private final String defaultBucket;

  public MinIOContainer() {
    this("minioadmin", "minioadmin123", "batch-test");
  }

  public MinIOContainer(String accessKey, String secretKey, String defaultBucket) {
    super(IMAGE);
    this.accessKey = accessKey;
    this.secretKey = secretKey;
    this.defaultBucket = defaultBucket;
    withExposedPorts(MINIO_API_PORT, MINIO_CONSOLE_PORT);
    withEnv("MINIO_ROOT_USER", accessKey);
    withEnv("MINIO_ROOT_PASSWORD", secretKey);
    withCommand("server", "/data", "--console-address", ":9001");
    waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
  }

  @Override
  public void start() {
    super.start();
    ensureBucketExists(defaultBucket);
  }

  public String getEndpoint() {
    return "http://" + getHost() + ":" + getMappedPort(MINIO_API_PORT);
  }

  public String getAccessKey() {
    return accessKey;
  }

  public String getSecretKey() {
    return secretKey;
  }

  public String getDefaultBucket() {
    return defaultBucket;
  }

  public S3Client client() {
    return S3Client.builder()
        .endpointOverride(URI.create(getEndpoint()))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .forcePathStyle(true)
        .region(Region.US_EAST_1)
        .build();
  }

  public void ensureBucketExists(String bucketName) {
    Instant deadline = BatchDateTimeSupport.utcNow().plus(Duration.ofMinutes(2));
    Exception lastFailure = null;
    while (BatchDateTimeSupport.utcNow().isBefore(deadline)) {
      try (S3Client client = client()) {
        if (!bucketExists(client, bucketName)) {
          client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
        }
        return;
      } catch (Exception exception) {
        lastFailure = exception;
        sleepBeforeRetry();
      }
    }
    throw new IllegalStateException("failed to ensure MinIO bucket: " + bucketName, lastFailure);
  }

  private boolean bucketExists(S3Client client, String bucketName) {
    try {
      client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
      return true;
    } catch (NoSuchBucketException notFound) {
      return false;
    }
  }

  private void sleepBeforeRetry() {
    try {
      TimeUnit.MILLISECONDS.sleep(500);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for MinIO readiness", interrupted);
    }
  }
}
