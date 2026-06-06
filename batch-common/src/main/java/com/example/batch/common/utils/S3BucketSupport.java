package com.example.batch.common.utils;

import com.example.batch.common.time.BatchDateTimeSupport;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 Bucket 自动初始化辅助工具类。 {@code ensureBucket} 在 Bucket 不存在时自动创建，成功返回 {@code true}，失败返回 {@code false}
 * 并记录警告日志。 为避免高频失败场景刷屏，日志输出有 5 分钟冷却窗口（per component+bucket 维度）。 S3Client 为 {@code null} 或 bucket
 * 名为空时直接返回 {@code false}，不抛异常。
 */
public final class S3BucketSupport {

  private static final long FAILURE_LOG_COOLDOWN_MILLIS = Duration.ofMinutes(5).toMillis();
  private static final ConcurrentHashMap<String, AtomicLong> LAST_FAILURE_LOG_AT =
      new ConcurrentHashMap<>();

  private S3BucketSupport() {}

  public static boolean ensureBucket(
      S3Client s3Client, String bucket, Logger log, String componentName, boolean autoCreate) {
    if (s3Client == null || !Texts.hasText(bucket)) {
      return false;
    }
    // autoCreate=false（托管云 S3/OSS/COS）：bucket 由外部预建、凭据通常无 CreateBucket（甚至无 HeadBucket）
    // 权限,这里不校验/不创建,直接放行;若 bucket 真缺失,在首次 put/get 时报明确错误。
    if (!autoCreate) {
      return true;
    }
    try {
      boolean exists;
      try {
        s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        exists = true;
      } catch (NoSuchBucketException e) {
        exists = false;
      } catch (S3Exception e) {
        // HEAD 无 body，部分后端把 bucket 不存在表现为通用 S3Exception(404) 而非类型化
        // NoSuchBucketException；按 404 判定为不存在，其余状态码（如 403 无权限）继续上抛。
        if (e.statusCode() != 404) {
          throw e;
        }
        exists = false;
      }
      if (!exists) {
        s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
      }
      LAST_FAILURE_LOG_AT.remove(cacheKey(componentName, bucket));
      return true;
    } catch (Exception ex) {
      String key = cacheKey(componentName, bucket);
      long now = BatchDateTimeSupport.utcEpochMillis();
      AtomicLong lastLoggedAt =
          LAST_FAILURE_LOG_AT.computeIfAbsent(key, ignored -> new AtomicLong(0L));
      long previous = lastLoggedAt.get();
      if (now - previous >= FAILURE_LOG_COOLDOWN_MILLIS
          && lastLoggedAt.compareAndSet(previous, now)) {
        log.warn(
            "{} s3 bucket ensure failed: bucket={}, cause={}",
            componentName,
            bucket,
            ex.getMessage());
      }
      return false;
    }
  }

  private static String cacheKey(String componentName, String bucket) {
    return (componentName == null ? "s3" : componentName) + "::" + bucket;
  }
}
