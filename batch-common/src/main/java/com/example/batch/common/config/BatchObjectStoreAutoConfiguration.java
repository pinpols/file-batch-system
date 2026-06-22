package com.example.batch.common.config;

import com.example.batch.common.health.ObjectStoreStartupCheck;
import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.common.storage.BatchObjectStore;
import com.example.batch.common.storage.EncryptingObjectStore;
import com.example.batch.common.storage.FilesystemObjectStore;
import com.example.batch.common.storage.MeteredObjectStore;
import com.example.batch.common.storage.S3ObjectStore;
import com.example.batch.common.utils.Texts;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
      ObjectProvider<BatchKmsProperties> kmsPropertiesProvider,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    BatchObjectCryptoService crypto = cryptoProvider.getIfAvailable();
    BatchKmsProperties kmsProperties = kmsPropertiesProvider.getIfAvailable();
    BatchObjectStore store;
    if (!encryptionProperties.isDecoratorEnabled()
        || securityProperties.isBypassMode()
        || crypto == null) {
      store = raw;
    } else {
      String defaultKeyRef = kmsProperties == null ? null : kmsProperties.getDefaultKeyRef();
      store =
          new EncryptingObjectStore(
              raw,
              crypto,
              securityProperties,
              defaultKeyRef,
              encryptionProperties.getMaxInMemoryEncryptBytes());
    }
    // 指标装饰挂最外层：有 MeterRegistry 即包裹（actuator 起则有），缺失则无声跳过，零回归。
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    return registry == null ? store : new MeteredObjectStore(store, registry);
  }

  /**
   * 对象存储启动冒烟自检（{@code batch.storage.startup-check.enabled=true}，默认开）。
   *
   * <p>启动期对配置 bucket 真做一遍 put→exists→statSize→get→list→delete 探针，任一步不符即 fail-fast 让 boot 失败。
   * 把「换了对象存储后端跑起来才发现不兼容 / endpoint 错 / bucket 无权限 / path-style 或 checksum 没配对」从运行时惊吓变成启动期失败。
   *
   * <p>{@link ConditionalOnBean} 守门：只有真存在 {@link BatchObjectStore} bean 的上下文才挂——很多 worker / 测试上下文
   * 根本不装配对象存储（无 S3Client / 后端未配），这些上下文不应被本自检牵连失败。bean 在 {@link #objectStore} 之后声明， 保证条件求值时能看到它。s3 /
   * filesystem 后端同样适用(后者校验 NAS 挂载可写)。
   */
  @Bean
  @ConditionalOnBean(BatchObjectStore.class)
  @ConditionalOnProperty(
      name = "batch.storage.startup-check.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public ApplicationRunner objectStoreStartupCheck(
      BatchObjectStore objectStore, S3StorageProperties properties) {
    return new ObjectStoreStartupCheck(objectStore, properties.getBucket());
  }
}
