package com.example.batch.common.config;

import com.example.batch.common.health.S3HealthIndicator;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@AutoConfiguration
@ConditionalOnClass(S3Client.class)
// 与 BatchObjectStoreAutoConfiguration 的 S3 分支同条件:backend=filesystem 时不实例化 S3Client/
// S3Presigner/健康探针——否则会对不存在的 endpoint 发 headBucket,把 readiness 拉 DOWN。
@ConditionalOnProperty(name = "batch.storage.backend", havingValue = "s3", matchIfMissing = true)
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3AutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public S3Client s3Client(S3StorageProperties p) {
    ApacheHttpClient.Builder http =
        ApacheHttpClient.builder()
            .connectionTimeout(Duration.ofMillis(p.getConnectTimeoutMs()))
            .socketTimeout(Duration.ofMillis(p.getReadTimeoutMs()));
    S3ClientBuilder b =
        S3Client.builder()
            .endpointOverride(URI.create(p.getEndpoint()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(p.getAccessKey(), p.getSecretKey())))
            .forcePathStyle(true)
            // 关掉 AWS SDK v2 ≥2.30 默认的 WHEN_SUPPORTED 请求校验和（CRC32 + aws-chunked trailer）：
            // 阿里 OSS / 腾讯 COS / GCS 等非 AWS 后端多不识别该 trailer，会回 501/签名错误。
            // WHEN_REQUIRED 恢复旧 MinIO SDK 行为（仅在 API 强制要求时才算 checksum）。
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .httpClientBuilder(http)
            .region(
                StringUtils.hasText(p.getRegion()) ? Region.of(p.getRegion()) : Region.US_EAST_1);
    return b.build();
  }

  @Bean
  @ConditionalOnMissingBean
  public S3Presigner s3Presigner(S3StorageProperties p) {
    return S3Presigner.builder()
        .endpointOverride(URI.create(p.getEndpoint()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(p.getAccessKey(), p.getSecretKey())))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .region(StringUtils.hasText(p.getRegion()) ? Region.of(p.getRegion()) : Region.US_EAST_1)
        .build();
  }

  @Bean("s3HealthIndicator")
  @ConditionalOnClass(HealthIndicator.class)
  @ConditionalOnProperty(
      prefix = "management.health.s3",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnMissingBean(name = "s3HealthIndicator")
  public HealthIndicator s3HealthIndicator(S3Client s3Client, S3StorageProperties p) {
    return new S3HealthIndicator(s3Client, p);
  }
}
