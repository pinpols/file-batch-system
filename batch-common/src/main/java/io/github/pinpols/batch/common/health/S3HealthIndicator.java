package io.github.pinpols.batch.common.health;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * S3 可用性探针：周期性 headBucket 调用，回 UP/DOWN。
 *
 * <p>readiness group 会把本探针纳入判断，S3 后端挂时 Pod 从 Service 端点摘除， 避免"接了请求再发现 IO 失败"。liveness 不含本探针——S3
 * 抖动不值得重启 JVM。
 *
 * <p>使用 S3AutoConfiguration 里配置过超时（connect 5s / read 30s）， 健康检查不会因 S3 全挂而阻塞健康端点响应。
 */
public class S3HealthIndicator implements HealthIndicator {

  private final S3Client s3Client;
  private final String bucket;

  public S3HealthIndicator(S3Client s3Client, S3StorageProperties properties) {
    this.s3Client = s3Client;
    this.bucket = properties.getBucket();
  }

  @Override
  public Health health() {
    try {
      s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
      return Health.up().withDetail("bucket", bucket).withDetail("exists", true).build();
    } catch (NoSuchBucketException ex) {
      return Health.up().withDetail("bucket", bucket).withDetail("exists", false).build();
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(S3HealthIndicator.class, "catch:Exception", ex);
      return Health.down(ex).withDetail("bucket", bucket).build();
    }
  }
}
