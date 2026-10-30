package io.github.pinpols.batch.securityscan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("安全扫描命令行参数解析:默认开关与 DAST 认证/API 规格参数映射")
class SecurityScanOptionsTest {

  @Test
  @DisplayName("解析模式/容错/演练三个开关后:帮助为假,模式为密钥扫描,容错与演练开关均为真")
  void shouldParseDefaultsAndFlags_whenModeAndSwitchesGiven() {
    SecurityScanOptions options = SecurityScanOptions.parse(
        new String[] {"--mode=secret", "--continue-on-error", "--dry-run"});

    assertThat(options.help()).isFalse();
    assertThat(options.mode()).isEqualTo(ScanMode.SECRET);
    assertThat(options.continueOnError()).isTrue();
    assertThat(options.dryRun()).isTrue();
  }

  @Test
  @DisplayName("解析 DAST 模式与认证/扫描参数后:模式、认证头名值、扫描类型与规格路径逐项对齐")
  void shouldParseDastAuthOptions_whenDastFlagsGiven() {
    SecurityScanOptions options = SecurityScanOptions.parse(new String[] {
      "--mode=dast",
      "--zap-auth-header-name=Cookie",
      "--zap-auth-header-value=batch_console_token=test",
      "--zap-scan=api",
      "--zap-api-spec=docs/api/console-api.openapi.yaml"
    });

    assertThat(options.mode()).isEqualTo(ScanMode.DAST);
    assertThat(options.zapAuthHeaderName()).isEqualTo("Cookie");
    assertThat(options.zapAuthHeaderValue()).isEqualTo("batch_console_token=test");
    assertThat(options.zapScan()).isEqualTo("api");
    assertThat(options.zapApiSpec()).isEqualTo("docs/api/console-api.openapi.yaml");
  }

  @Test
  @DisplayName("无效扫描类型及缺少 API 规格仍被拒绝")
  void shouldRejectInvalidDastOptions() {
    assertThatThrownBy(() -> SecurityScanOptions.parse(new String[] {"--zap-scan=invalid"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--zap-scan must be one of");
    assertThatThrownBy(() -> SecurityScanOptions.parse(new String[] {"--zap-scan=api"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--zap-scan=api requires --zap-api-spec");
  }
}
