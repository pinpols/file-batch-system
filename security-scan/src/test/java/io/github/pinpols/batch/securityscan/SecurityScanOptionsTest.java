package io.github.pinpols.batch.securityscan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("安全扫描命令行参数解析:默认开关与 DAST 认证/API 规格参数映射")
class SecurityScanOptionsTest {

    @Test
    @DisplayName("解析模式/容错/演练三个开关后:帮助为假,模式为密钥扫描,容错与演练开关均为真")
    void shouldParseDefaultsAndFlags_whenModeAndSwitchesGiven() {
        SecurityScanOptions options = SecurityScanOptions.parse(new String[]{"--mode=secret", "--continue-on-error", "--dry-run"});

        assertFalse(options.help());
        assertEquals(ScanMode.SECRET, options.mode());
        assertTrue(options.continueOnError());
        assertTrue(options.dryRun());
    }

    @Test
    @DisplayName("解析 DAST 模式与认证/扫描参数后:模式、认证头名值、扫描类型与规格路径逐项对齐")
    void shouldParseDastAuthOptions_whenDastFlagsGiven() {
        SecurityScanOptions options = SecurityScanOptions.parse(new String[]{
                "--mode=dast",
                "--zap-auth-header-name=Cookie",
                "--zap-auth-header-value=batch_console_token=test",
                "--zap-scan=api",
                "--zap-api-spec=docs/api/console-api.openapi.yaml"
        });

        assertEquals(ScanMode.DAST, options.mode());
        assertEquals("Cookie", options.zapAuthHeaderName());
        assertEquals("batch_console_token=test", options.zapAuthHeaderValue());
        assertEquals("api", options.zapScan());
        assertEquals("docs/api/console-api.openapi.yaml", options.zapApiSpec());
    }
}
