package com.example.batch.common.utils;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * MinIO Bucket 自动初始化辅助工具类。 {@code ensureBucket} 在 Bucket 不存在时自动创建，成功返回 {@code true}，失败返回 {@code
 * false} 并记录警告日志。 为避免高频失败场景刷屏，日志输出有 5 分钟冷却窗口（per component+bucket 维度）。 MinioClient 为 {@code null} 或
 * bucket 名为空时直接返回 {@code false}，不抛异常。
 */
public final class MinioBucketSupport {

  private static final long FAILURE_LOG_COOLDOWN_MILLIS = Duration.ofMinutes(5).toMillis();
  private static final ConcurrentHashMap<String, AtomicLong> LAST_FAILURE_LOG_AT =
      new ConcurrentHashMap<>();

  private MinioBucketSupport() {}

  public static boolean ensureBucket(
      MinioClient minioClient, String bucket, Logger log, String componentName) {
    if (minioClient == null || !Texts.hasText(bucket)) {
      return false;
    }
    try {
      boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
      if (!exists) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      }
      LAST_FAILURE_LOG_AT.remove(cacheKey(componentName, bucket));
      return true;
    } catch (Exception ex) {
      String key = cacheKey(componentName, bucket);
      long now = System.currentTimeMillis();
      AtomicLong lastLoggedAt =
          LAST_FAILURE_LOG_AT.computeIfAbsent(key, ignored -> new AtomicLong(0L));
      long previous = lastLoggedAt.get();
      if (now - previous >= FAILURE_LOG_COOLDOWN_MILLIS
          && lastLoggedAt.compareAndSet(previous, now)) {
        log.warn(
            "{} minio bucket ensure failed: bucket={}, cause={}",
            componentName,
            bucket,
            ex.getMessage());
      }
      return false;
    }
  }

  private static String cacheKey(String componentName, String bucket) {
    return (componentName == null ? "minio" : componentName) + "::" + bucket;
  }
}
