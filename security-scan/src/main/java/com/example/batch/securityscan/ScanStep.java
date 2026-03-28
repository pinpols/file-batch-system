package com.example.batch.securityscan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public enum ScanStep {
    SECRET("secret"),
    DEPS("deps"),
    SAST("sast"),
    FILESYSTEM("filesystem"),
    IMAGE("image"),
    DAST("dast");

    private final String displayName;

    ScanStep(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public List<ExternalCommand> buildCommands(SecurityScanOptions options) {
        Path root = options.root();
        return switch (this) {
            case SECRET -> List.of(new ExternalCommand(
                    this,
                    "gitleaks",
                    list(options.gitleaksCommand(), "detect", "--source", root.toString(), "--redact", "--no-banner", "--report-format", "json", "--report-path", options.reportDir().resolve("gitleaks.json").toString()),
                    root
            ));
            case DEPS -> List.of(new ExternalCommand(
                    this,
                    "dependency-check",
                    list(options.mvnCommand(), "-P", "compliance", "org.owasp:dependency-check-maven:check", "-DfailBuildOnCVSS=7", "-DoutputDirectory=" + options.reportDir().resolve("dependency-check"), "-Dformat=HTML,JSON"),
                    root
            ));
            case SAST -> List.of(new ExternalCommand(
                    this,
                    "semgrep",
                    list(options.semgrepCommand(), "scan", "--config", "auto", "--json", "--output", options.reportDir().resolve("semgrep.json").toString(), root.toString()),
                    root
            ));
            case FILESYSTEM -> List.of(new ExternalCommand(
                    this,
                    "trivy-fs",
                    list(options.trivyCommand(), "fs", root.toString(), "--scanners", "vuln,secret,misconfig", "--format", "json", "--output", options.reportDir().resolve("trivy-fs.json").toString()),
                    root
            ));
            case IMAGE -> List.of(
                    new ExternalCommand(
                            this,
                            "docker-build",
                            list(options.dockerCommand(), "build", "-f", "docker/Dockerfile.app", "--build-arg", "MODULE=batch-console-api", "-t", options.imageName(), root.toString()),
                            root
                    ),
                    new ExternalCommand(
                            this,
                            "trivy-image",
                            list(options.trivyCommand(), "image", options.imageName(), "--format", "json", "--output", options.reportDir().resolve("trivy-image.json").toString()),
                            root
                    )
            );
            case DAST -> List.of(new ExternalCommand(
                    this,
                    "zap-baseline",
                    list(options.dockerCommand(), "run", "--rm", "-t", "-v", options.reportDir().toString() + ":/zap/wrk", options.zapImage(), "zap-baseline.py", "-t", options.targetUrl(), "-r", Path.of(options.zapReport()).getFileName().toString()),
                    root
            ));
        };
    }

    private static List<String> list(String first, String... rest) {
        List<String> command = new ArrayList<>(rest.length + 1);
        command.add(first);
        for (String value : rest) {
            command.add(value);
        }
        return command;
    }
}
