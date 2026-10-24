package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.storage.NamespacedObjectStore;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

@DisplayName("对象存储的部署命名空间配置")
class BatchObjectStoreNamespaceConfigurationTest {

  @DisplayName("统一对象存储边界应用部署命名空间")
  @Test
  void shouldApplyDeploymentNamespaceAtTheUnifiedObjectStoreBoundary() {
    BatchObjectStore delegate = mock(BatchObjectStore.class);
    KafkaNamespaceProperties namespaceProperties = new KafkaNamespaceProperties();
    namespaceProperties.setNamespace("blue");
    BatchSecurityProperties securityProperties = new BatchSecurityProperties();
    ObjectStoreEncryptionProperties encryptionProperties = new ObjectStoreEncryptionProperties();
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();

    BatchObjectStore store = configuration(namespaceProperties)
        .objectStore(
            new BatchObjectStoreAutoConfiguration.RawObjectStoreBackend(delegate),
            securityProperties,
            encryptionProperties,
            beanFactory.getBeanProvider(BatchObjectCryptoService.class),
            beanFactory.getBeanProvider(BatchKmsProperties.class),
            beanFactory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));

    assertThat(store).isInstanceOf(NamespacedObjectStore.class);
    store.put("bucket", "result.csv", new ByteArrayInputStream(new byte[] {1}), 1, "text/csv");
    verify(delegate).put(eq("bucket"), eq("blue/result.csv"), any(), eq(1L), eq("text/csv"));
  }

  @DisplayName("本地单部署保持原对象键")
  @Test
  void shouldLeaveLocalSingleDeploymentStorageKeysUnchanged() {
    BatchObjectStore delegate = mock(BatchObjectStore.class);
    KafkaNamespaceProperties namespaceProperties = new KafkaNamespaceProperties();
    BatchObjectStore store = configuration(namespaceProperties)
        .objectStore(
            new BatchObjectStoreAutoConfiguration.RawObjectStoreBackend(delegate),
            new BatchSecurityProperties(),
            new ObjectStoreEncryptionProperties(),
            new StaticListableBeanFactory().getBeanProvider(BatchObjectCryptoService.class),
            new StaticListableBeanFactory().getBeanProvider(BatchKmsProperties.class),
            new StaticListableBeanFactory()
                .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));

    assertThat(store).isSameAs(delegate);
    store.put("bucket", "result.csv", new ByteArrayInputStream(new byte[] {1}), 1, "text/csv");
    verify(delegate).put(eq("bucket"), eq("result.csv"), any(), eq(1L), eq("text/csv"));
  }
  private static BatchObjectStoreAutoConfiguration configuration(
      KafkaNamespaceProperties namespaceProperties) {
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("deploymentNamespace", namespaceProperties);
    org.springframework.beans.factory.ObjectProvider<KafkaNamespaceProperties> namespaceProvider =
        beanFactory.getBeanProvider(KafkaNamespaceProperties.class);
    return new BatchObjectStoreAutoConfiguration(namespaceProvider);
  }
}
