package io.github.pinpols.batch.securityscan;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityScanOrchestratorTest {

    @Test
    void buildSecretStepCommand() {
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
    void buildDastApiScanCommandMountsOpenApiSpec() {
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
