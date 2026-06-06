package com.example.batch.common.config;

import com.example.batch.common.storage.BatchObjectStore;
import com.example.batch.common.storage.S3ObjectStore;
import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * {@link BatchObjectStore} 后端选择器。由 {@code batch.storage.backend} 决定装配哪个实现，默认 {@code s3}。
 *
 * <p>FileSystem 后端为阶段二内容，此处不实现；当前仅 {@code s3} 一种装配（{@code matchIfMissing=true}）。
 */
@AutoConfiguration(after = MinioAutoConfiguration.class)
@ConditionalOnClass(MinioClient.class)
@EnableConfigurationProperties(S3StorageProperties.class)
public class BatchObjectStoreAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(BatchObjectStore.class)
  @ConditionalOnProperty(name = "batch.storage.backend", havingValue = "s3", matchIfMissing = true)
  public BatchObjectStore s3ObjectStore(MinioClient minioClient, S3StorageProperties properties) {
    return new S3ObjectStore(minioClient, properties);
  }
}
