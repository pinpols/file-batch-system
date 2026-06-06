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
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@AutoConfiguration
@ConditionalOnClass(S3Client.class)
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
