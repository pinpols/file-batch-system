package io.github.pinpols.batch.securityscan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("安全扫描步骤命令构建:各步骤展示名与容器命令拼装,含 API 规格挂载路径")
class SecurityScanOrchestratorTest {

    @Test
    @DisplayName("密钥扫描步骤:展示名为 secret,首条命令为 gitleaks")
    void shouldBuildGitleaksCommand_whenSecretStep() {
        SecurityScanOptions options = new SecurityScanOptions(
                false,
                ScanMode.SECRET,
                Path.of(".").toAbsolutePath().normalize(),
                "http://localhost:8080",
                "batch-console-api:local",
                "ghcr.io/zaproxy/zaproxy:stable",
                "target/zap-report.html",
                "Authorization",
                null,
                "baseline",
                null,
                false,
                Path.of("target/security-scan"),
                "mvn",
                "gitleaks",
                "semgrep",
                "trivy",
                "docker",
                true,
                false
        );

        ScanStep step = ScanStep.SECRET;
        assertEquals("secret", step.displayName());
        assertEquals("gitleaks", step.buildCommands(options).get(0).commandLine().getFirst());
    }

    @Test
    @DisplayName("DAST API 扫描步骤:命令含 api 扫描脚本与 openapi 格式,并把规格目录只读挂载进容器")
    void shouldMountOpenApiSpec_whenDastApiScanStep() {
        Path root = Path.of(".").toAbsolutePath().normalize();
        SecurityScanOptions options = new SecurityScanOptions(
                false,
                ScanMode.DAST,
                root,
                "http://localhost:18080",
                "batch-console-api:local",
                "ghcr.io/zaproxy/zaproxy:stable",
                "target/zap-report.html",
                "Cookie",
                "batch_console_token=test",
                "api",
                "docs/api/console-api.openapi.yaml",
                true,
                Path.of("target/security-scan"),
                "mvn",
                "gitleaks",
                "semgrep",
                "trivy",
                "docker",
                true,
                false
        );

        var command = ScanStep.DAST.buildCommands(options).getFirst().commandLine();

        assertTrue(command.contains("zap-api-scan.py"));
        assertTrue(command.contains("-f"));
        assertTrue(command.contains("openapi"));
        assertTrue(command.contains(root + ":/zap/src:ro"));
        assertTrue(command.contains("/zap/src/docs/api/console-api.openapi.yaml"));
    }
}
