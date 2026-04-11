package com.example.batch.common.utils;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;

import org.slf4j.Logger;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class MinioBucketSupport {

    private static final long FAILURE_LOG_COOLDOWN_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final ConcurrentHashMap<String, AtomicLong> LAST_FAILURE_LOG_AT =
            new ConcurrentHashMap<>();

    private MinioBucketSupport() {}

    public static boolean ensureBucket(
            MinioClient minioClient, String bucket, Logger log, String componentName) {
        if (minioClient == null || !StringUtils.hasText(bucket)) {
            return false;
        }
        try {
            boolean exists =
                    minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
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
