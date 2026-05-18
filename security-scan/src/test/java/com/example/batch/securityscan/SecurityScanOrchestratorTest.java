package com.example.batch.securityscan;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
