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
@EnableConfigurationProperties(S3StorageProperties.class)
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
      MinioClient minioClient, S3StorageProperties properties) {
    return new MinioHealthIndicator(minioClient, properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public MinioClient minioClient(S3StorageProperties properties) {
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

    MinioClient.Builder builder =
        MinioClient.builder()
            .endpoint(properties.getEndpoint())
            .credentials(properties.getAccessKey(), properties.getSecretKey())
            .httpClient(httpClient);
    // region 仅在配置非空时传入：AWS/OSS/COS 的 SigV4 需要;自建 MinIO/Ceph 留空保持默认行为。
    // MinIO SDK 是通用 S3 客户端,path-style / virtual-hosted-style 寻址 + 签名它自动处理。
    if (properties.getRegion() != null && !properties.getRegion().isBlank()) {
      builder.region(properties.getRegion());
    }
    return builder.build();
  }
}
