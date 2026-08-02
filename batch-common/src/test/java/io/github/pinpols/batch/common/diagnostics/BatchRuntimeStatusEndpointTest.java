package io.github.pinpols.batch.common.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

class BatchRuntimeStatusEndpointTest {

  @Test
  void returnsOnlyRedactedEffectiveRuntimeStatus() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty("spring.application.name", "batch-test")
        .withProperty("batch.storage.backend", "s3");
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(false);
    security.setInternalSecret("strong-secret-that-must-not-appear");
    S3StorageProperties s3 = new S3StorageProperties();
    s3.setEndpoint("https://s3.example.internal:9443");
    s3.setBucket("batch-prod");
    s3.setAccessKey("access");
    s3.setSecretKey("secret-that-must-not-appear");

    BatchRuntimeStatusEndpoint endpoint = new BatchRuntimeStatusEndpoint(
        environment, provider(security), provider(s3), provider(null), provider(null));

    Map<String, Object> status = endpoint.status();

    assertThat(status).containsEntry("application", "batch-test");
    assertThat(status.get("storage").toString()).contains("s3.example.internal");
    assertThat(status.toString()).doesNotContain("strong-secret-that-must-not-appear");
    assertThat(status.toString()).doesNotContain("secret-that-must-not-appear");
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }
}
