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
                    // R7 OSS 扫描 followup：纯 Java backend 不需要 JS / Node / Ruby analyzer。
                    // 关掉它们避免 RetireJS 拉 raw.githubusercontent.com（VPN/代理 DNS 劫持时整个扫描 abort）
                    // 以及 NodeJS / Ruby 等 noise，同时显著降低扫描耗时。
                    list(options.mvnCommand(), "-P", "compliance", "org.owasp:dependency-check-maven:check",
                            "-DfailBuildOnCVSS=7",
                            "-DoutputDirectory=" + options.reportDir().resolve("dependency-check"),
                            // dependency-check 12.x 把 -Dformat 的 CSV 写法当单一模板名解析（HTML,JSON.vsl
                            // 文件找不到），必须改用 -Dformats 重复传 或 ALL。这里用 ALL 一次生成全格式报告。
                            "-Dformats=ALL",
                            "-DretireJsAnalyzerEnabled=false",
                            "-DnodeAnalyzerEnabled=false",
                            "-DnodeAuditAnalyzerEnabled=false",
                            "-DyarnAuditAnalyzerEnabled=false",
                            "-DpnpmAuditAnalyzerEnabled=false",
                            "-DrubygemsAnalyzerEnabled=false",
                            "-DbundleAuditAnalyzerEnabled=false",
                            "-DassemblyAnalyzerEnabled=false"),
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
                    dastCommand(options),
                    root
            ));
        };
    }

    private static List<String> dastCommand(SecurityScanOptions options) {
        List<String> command = new ArrayList<>(list(
                options.dockerCommand(),
                "run",
                "--rm",
                "-t",
                "-v",
                options.reportDir().toString() + ":/zap/wrk",
                options.zapImage(),
                "zap-baseline.py",
                "-t",
                options.targetUrl(),
                "-r",
                Path.of(options.zapReport()).getFileName().toString()));
        if (options.zapAuthHeaderValue() != null && !options.zapAuthHeaderValue().isBlank()) {
            command.add("-z");
            command.add(
                    "-config replacer.full_list(0).description=batch-console-auth"
                            + " -config replacer.full_list(0).enabled=true"
                            + " -config replacer.full_list(0).matchtype=REQ_HEADER"
                            + " -config replacer.full_list(0).matchstr=" + options.zapAuthHeaderName()
                            + " -config replacer.full_list(0).replacement=" + options.zapAuthHeaderValue());
        }
        return command;
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
