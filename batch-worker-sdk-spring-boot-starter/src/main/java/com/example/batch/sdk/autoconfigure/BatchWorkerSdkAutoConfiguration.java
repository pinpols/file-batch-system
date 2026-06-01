package com.example.batch.sdk.autoconfigure;

import com.example.batch.sdk.client.BatchPlatformClient;
import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.idempotent.SdkIdempotencyStore;
import com.example.batch.sdk.task.SdkTaskHandler;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(BatchPlatformClient.class)
@EnableConfigurationProperties(BatchWorkerSdkProperties.class)
public class BatchWorkerSdkAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(BatchPlatformClientConfig.class)
  BatchPlatformClientConfig batchPlatformClientConfig(BatchWorkerSdkProperties properties) {
    return properties.toConfig();
  }

  @Bean
  @ConditionalOnMissingBean(BatchPlatformClient.class)
  BatchPlatformClient batchPlatformClient(
      BatchPlatformClientConfig config,
      ObjectProvider<SdkTaskHandler> handlerProvider,
      ObjectProvider<SdkIdempotencyStore> idempotencyStoreProvider) {
    BatchPlatformClient.Builder builder = BatchPlatformClient.builder(config);
    SdkIdempotencyStore idempotencyStore = idempotencyStoreProvider.getIfUnique();
    if (idempotencyStore != null) {
      builder.idempotencyStore(idempotencyStore);
    }
    List<SdkTaskHandler> handlers = handlerProvider.orderedStream().toList();
    handlers.forEach(builder::register);
    return builder.build();
  }

  @Bean
  @ConditionalOnProperty(
      name = "batch.worker-sdk.enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnMissingBean(BatchPlatformClientLifecycle.class)
  BatchPlatformClientLifecycle batchPlatformClientLifecycle(
      BatchPlatformClient client, BatchWorkerSdkProperties properties) {
    return new BatchPlatformClientLifecycle(client, properties);
  }
}
