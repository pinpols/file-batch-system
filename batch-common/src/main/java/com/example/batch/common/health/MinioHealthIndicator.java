package com.example.batch.common.health;

import com.example.batch.common.config.S3StorageProperties;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * MinIO 可用性探针：周期性 bucketExists 调用，回 UP/DOWN。
 *
 * <p>readiness group 会把本探针纳入判断，MinIO 挂时 Pod 从 Service 端点摘除， 避免"接了请求再发现 IO 失败"。liveness 不含本探针——MinIO
 * 抖动不值得重启 JVM。
 *
 * <p>使用 MinioAutoConfiguration 里配置过超时（connect 5s / read 30s）， 健康检查不会因 MinIO 全挂而阻塞健康端点响应。
 */
public class MinioHealthIndicator implements HealthIndicator {

  private final MinioClient minioClient;
  private final String bucket;

  public MinioHealthIndicator(MinioClient minioClient, S3StorageProperties properties) {
    this.minioClient = minioClient;
    this.bucket = properties.getBucket();
  }

  @Override
  public Health health() {
    try {
      boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
      return Health.up().withDetail("bucket", bucket).withDetail("exists", exists).build();
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(MinioHealthIndicator.class, "catch:Exception", ex);

      return Health.down(ex).withDetail("bucket", bucket).build();
    }
  }
}
