package com.example.batch.common.config;

import com.example.batch.common.health.MinioHealthIndicator;
import io.minio.MinioClient;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(MinioClient.class)
@EnableConfigurationProperties(MinioStorageProperties.class)
public class MinioAutoConfiguration {

  @Bean("minioHealthIndicator")
  @ConditionalOnClass(HealthIndicator.class)
  @ConditionalOnProperty(
      prefix = "management.health.minio",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnMissingBean(name = "minioHealthIndicator")
  public HealthIndicator minioHealthIndicator(
      MinioClient minioClient, MinioStorageProperties properties) {
    return new MinioHealthIndicator(minioClient, properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public MinioClient minioClient(MinioStorageProperties properties) {
    // 显式 OkHttp client：MinIO 默认的 http client 超时为 0（= 永久等待），
    // MinIO 宕机/网络断开时 worker 线程会无限挂起。这里收紧超时并打开
    // 连接失败重试（底层 DNS/socket 抖动时 OkHttp 会换 IP 重试一次）。
    OkHttpClient httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(properties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
            .readTimeout(properties.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
            .writeTimeout(properties.getWriteTimeoutMs(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build();

    return MinioClient.builder()
        .endpoint(properties.getEndpoint())
        .credentials(properties.getAccessKey(), properties.getSecretKey())
        .httpClient(httpClient)
        .build();
  }
}
