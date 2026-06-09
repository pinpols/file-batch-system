package com.example.batch.common.config;

import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.common.storage.BatchObjectStore;
import com.example.batch.common.storage.EncryptingObjectStore;
import com.example.batch.common.storage.FilesystemObjectStore;
import com.example.batch.common.storage.S3ObjectStore;
import com.example.batch.common.utils.Texts;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * {@link BatchObjectStore} 后端选择器 + 加密装饰层装配（Phase 2）。
 *
 * <p>{@code batch.storage.backend} 决定 raw 实现：
 *
 * <ul>
 *   <li>{@code s3}（默认，{@code matchIfMissing=true}）→ {@link S3ObjectStore}
 *   <li>{@code filesystem} → {@link FilesystemObjectStore}（NAS / 本地主存储；presign 走应用代下端点）
 * </ul>
 *
 * <p>加密装饰：当 {@code batch.storage.encryption.decorator-enabled=true}（默认 false 保持零回归）且 {@code
 * batch.security.bypass-mode=false} 时，最外层 {@link BatchObjectStore} bean 是 {@link
 * EncryptingObjectStore} 包裹的 raw store；否则直接暴露 raw store。
 *
 * <p>raw store bean 命名 {@code rawObjectStore}（用 {@link Qualifier} 区分），业务代码注入的 {@link
 * BatchObjectStore} 始终是最外层 bean。
 */
@AutoConfiguration(after = {S3AutoConfiguration.class, BatchObjectCryptoAutoConfiguration.class})
@ConditionalOnClass(S3Client.class)
@EnableConfigurationProperties({
  S3StorageProperties.class,
  FilesystemStorageProperties.class,
  ObjectStoreEncryptionProperties.class
})
public class BatchObjectStoreAutoConfiguration {

  /** S3 后端 raw 实现（默认）。 */
  @Bean(name = "rawObjectStore")
  @ConditionalOnMissingBean(name = "rawObjectStore")
  @ConditionalOnProperty(name = "batch.storage.backend", havingValue = "s3", matchIfMissing = true)
  public BatchObjectStore s3RawObjectStore(
      S3Client s3Client, S3Presigner presigner, S3StorageProperties properties) {
    return new S3ObjectStore(s3Client, presigner, properties);
  }

  /** FS 后端 raw 实现。 */
  @Bean(name = "rawObjectStore")
  @ConditionalOnMissingBean(name = "rawObjectStore")
  @ConditionalOnProperty(name = "batch.storage.backend", havingValue = "filesystem")
  public BatchObjectStore filesystemRawObjectStore(
      FilesystemStorageProperties properties, BatchSecurityProperties securityProperties) {
    String secret =
        Texts.hasText(properties.getPresignSecret())
            ? properties.getPresignSecret()
            : securityProperties.getInternalSecret();
    return new FilesystemObjectStore(properties.getRoot(), properties.getDownloadBaseUrl(), secret);
  }

  /**
   * 最外层 {@link BatchObjectStore} bean——业务代码注入此。
   *
   * <p>加密装饰启用 + 非 bypass → {@link EncryptingObjectStore} 包裹 raw；否则直接暴露 raw。
   */
  @Bean
  @ConditionalOnMissingBean(BatchObjectStore.class)
  public BatchObjectStore objectStore(
      @Qualifier("rawObjectStore") BatchObjectStore raw,
      BatchSecurityProperties securityProperties,
      ObjectStoreEncryptionProperties encryptionProperties,
      ObjectProvider<BatchObjectCryptoService> cryptoProvider,
      ObjectProvider<BatchKmsProperties> kmsPropertiesProvider) {
    BatchObjectCryptoService crypto = cryptoProvider.getIfAvailable();
    BatchKmsProperties kmsProperties = kmsPropertiesProvider.getIfAvailable();
    if (!encryptionProperties.isDecoratorEnabled()
        || securityProperties.isBypassMode()
        || crypto == null) {
      return raw;
    }
    String defaultKeyRef = kmsProperties == null ? null : kmsProperties.getDefaultKeyRef();
    return new EncryptingObjectStore(
        raw,
        crypto,
        securityProperties,
        defaultKeyRef,
        encryptionProperties.getMaxInMemoryEncryptBytes());
  }
}
