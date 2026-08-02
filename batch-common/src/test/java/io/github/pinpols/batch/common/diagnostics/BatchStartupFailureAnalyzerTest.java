package io.github.pinpols.batch.common.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

class BatchStartupFailureAnalyzerTest {

  private final BatchStartupFailureAnalyzer analyzer = new BatchStartupFailureAnalyzer();

  @Test
  void analyzesProductionInternalSecretFailures() {
    FailureAnalysis analysis = analyzer.analyze(new IllegalStateException(
        "FATAL: production secret is not configured: batch.security.internal-secret is empty"));

    assertThat(analysis).isNotNull();
    assertThat(analysis.getDescription()).contains("生产内部 API 共享密钥");
    assertThat(analysis.getAction()).contains("BATCH_INTERNAL_SECRET");
  }

  @Test
  void analyzesConsoleJwtSecretFailures() {
    FailureAnalysis analysis = analyzer.analyze(
        new IllegalStateException("FATAL: production batch.console.security.jwt-secret is empty"));

    assertThat(analysis).isNotNull();
    assertThat(analysis.getDescription()).contains("Console JWT");
    assertThat(analysis.getAction()).contains("BATCH_CONSOLE_JWT_SECRET");
  }

  @Test
  void analyzesObjectStorageCredentialFailures() {
    FailureAnalysis analysis = analyzer.analyze(new IllegalStateException(
        "FATAL: production object-storage credentials are not configured"));

    assertThat(analysis).isNotNull();
    assertThat(analysis.getDescription()).contains("对象存储凭据");
    assertThat(analysis.getAction()).contains("BATCH_S3_ACCESS_KEY");
  }

  @Test
  void ignoresUnrelatedIllegalStateExceptions() {
    FailureAnalysis analysis = analyzer.analyze(new IllegalStateException("unrelated failure"));

    assertThat(analysis).isNull();
  }
}
