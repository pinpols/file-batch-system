package io.github.pinpols.batch.common.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

@DisplayName("启动失败分析器:生产密钥与对象存储凭据缺失的排障提示")
class BatchStartupFailureAnalyzerTest {

  private final BatchStartupFailureAnalyzer analyzer = new BatchStartupFailureAnalyzer();

  @Test
  @DisplayName("生产内部共享密钥缺失时给出对应配置项提示")
  void shouldReportInternalSecretHint_whenProductionSecretMissing() {
    FailureAnalysis analysis = analyzer.analyze(new IllegalStateException(
        "FATAL: production secret is not configured: batch.security.internal-secret is empty"));

    assertThat(analysis).isNotNull();
    assertThat(analysis.getDescription()).contains("生产内部 API 共享密钥");
    assertThat(analysis.getAction()).contains("BATCH_INTERNAL_SECRET");
  }

  @Test
  @DisplayName("生产控制台令牌密钥缺失时给出对应配置项提示")
  void shouldReportConsoleJwtHint_whenProductionJwtSecretMissing() {
    FailureAnalysis analysis = analyzer.analyze(
        new IllegalStateException("FATAL: production batch.console.security.jwt-secret is empty"));

    assertThat(analysis).isNotNull();
    assertThat(analysis.getDescription()).contains("Console JWT");
    assertThat(analysis.getAction()).contains("BATCH_CONSOLE_JWT_SECRET");
  }

  @Test
  @DisplayName("生产对象存储凭据缺失时给出对应配置项提示")
  void shouldReportObjectStorageHint_whenCredentialsMissing() {
    FailureAnalysis analysis = analyzer.analyze(new IllegalStateException(
        "FATAL: production object-storage credentials are not configured"));

    assertThat(analysis).isNotNull();
    assertThat(analysis.getDescription()).contains("对象存储凭据");
    assertThat(analysis.getAction()).contains("BATCH_S3_ACCESS_KEY");
  }

  @Test
  @DisplayName("与本启动校验无关的非法状态异常不产生分析结果")
  void shouldReturnNull_whenIllegalStateExceptionIsUnrelated() {
    FailureAnalysis analysis = analyzer.analyze(new IllegalStateException("unrelated failure"));

    assertThat(analysis).isNull();
  }
}
