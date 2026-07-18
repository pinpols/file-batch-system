package io.github.pinpols.batch.orchestrator.application.service.dryrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.s3.S3Client;

class DryRunObjectStorageProbeTest {

  @Test
  void shouldUseConfiguredBucketAndRejectInvalidName() {
    ObjectProvider<S3Client> clientProvider = provider(null);
    S3StorageProperties properties = new S3StorageProperties();
    properties.setBucket("invalid_bucket");
    DryRunObjectStorageProbe probe =
        new DryRunObjectStorageProbe(clientProvider, provider(properties));
    List<DryRunFinding> findings = new ArrayList<>();

    int probed = probe.probe(Map.of(), findings);

    assertThat(probed).isEqualTo(1);
    assertThat(findings).extracting(DryRunFinding::code).containsExactly("EXEC_S3_BUCKET_INVALID");
  }

  @Test
  void shouldKeepRegexOnlyFallbackWhenClientUnavailable() {
    DryRunObjectStorageProbe probe = new DryRunObjectStorageProbe(provider(null), provider(null));
    List<DryRunFinding> findings = new ArrayList<>();

    int probed = probe.probe(Map.of("s3Bucket", "batch-results"), findings);

    assertThat(probed).isEqualTo(1);
    assertThat(findings)
        .extracting(DryRunFinding::code)
        .containsExactly("EXEC_S3_CLIENT_UNAVAILABLE");
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }
}
