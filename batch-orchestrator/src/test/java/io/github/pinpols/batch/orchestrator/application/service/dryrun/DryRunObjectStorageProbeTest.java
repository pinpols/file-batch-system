package io.github.pinpols.batch.orchestrator.application.service.dryrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.storage.S3DryRunObjectStorageProbe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.s3.S3Client;

@DisplayName("试运行对象存储探测: 桶名称校验与客户端不可用时的降级口径")
class DryRunObjectStorageProbeTest {

  @Test
  @DisplayName("按配置的存储桶名称探测,名称非法时记录桶配置非法的发现项")
  void shouldUseConfiguredBucketAndRejectInvalidName() {
    ObjectProvider<S3Client> clientProvider = provider(null);
    S3StorageProperties properties = new S3StorageProperties();
    properties.setBucket("invalid_bucket");
    DryRunObjectStorageProbe probe =
        new S3DryRunObjectStorageProbe(clientProvider, provider(properties));
    List<DryRunFinding> findings = new ArrayList<>();

    int probed = probe.probe(Map.of(), findings);

    assertThat(probed).isEqualTo(1);
    assertThat(findings).extracting(DryRunFinding::code).containsExactly("EXEC_S3_BUCKET_INVALID");
  }

  @Test
  @DisplayName("存储客户端不可用时退化为仅按命名规则匹配,仍完成一次探测")
  void shouldKeepRegexOnlyFallbackWhenClientUnavailable() {
    DryRunObjectStorageProbe probe = new S3DryRunObjectStorageProbe(provider(null), provider(null));
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
