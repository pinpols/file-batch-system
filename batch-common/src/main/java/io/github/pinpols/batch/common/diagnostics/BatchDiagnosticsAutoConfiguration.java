package io.github.pinpols.batch.common.diagnostics;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.FilesystemStorageProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.config.StorageBackendGuardProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@ConditionalOnClass(Endpoint.class)
public class BatchDiagnosticsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  BatchRuntimeStatusEndpoint batchRuntimeStatusEndpoint(
      Environment environment,
      ObjectProvider<BatchSecurityProperties> securityProvider,
      ObjectProvider<S3StorageProperties> s3Provider,
      ObjectProvider<FilesystemStorageProperties> filesystemProvider,
      ObjectProvider<StorageBackendGuardProperties> guardProvider) {
    return new BatchRuntimeStatusEndpoint(
        environment, securityProvider, s3Provider, filesystemProvider, guardProvider);
  }
}
