package com.example.batch.securityscan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
                    // 若仓库根存在 .gitleaks.toml,显式 --config 传入(extend useDefault=true + allowlist
                    // 排除 build artifact / 已审计 fixture);否则 fall back 默认规则。
                    Files.exists(root.resolve(".gitleaks.toml"))
                            ? list(options.gitleaksCommand(), "detect", "--source", root.toString(), "--config", root.resolve(".gitleaks.toml").toString(), "--redact", "--no-banner", "--report-format", "json", "--report-path", options.reportDir().resolve("gitleaks.json").toString())
                            : list(options.gitleaksCommand(), "detect", "--source", root.toString(), "--redact", "--no-banner", "--report-format", "json", "--report-path", options.reportDir().resolve("gitleaks.json").toString()),
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
                options.reportDir().toString() + ":/zap/wrk"));
        String scan = options.zapScan().toLowerCase(Locale.ROOT);
        if ("api".equals(scan) && isLocalSpec(options.zapApiSpec())) {
            command.add("-v");
            command.add(options.root().toString() + ":/zap/src:ro");
        }
        command.add(options.zapImage());
        command.add(switch (scan) {
            case "api" -> "zap-api-scan.py";
            case "full" -> "zap-full-scan.py";
            default -> "zap-baseline.py";
        });
        command.add("-t");
        command.add("api".equals(scan) ? zapApiTarget(options) : options.targetUrl());
        if ("api".equals(scan)) {
            command.add("-f");
            command.add("openapi");
        }
        command.add("-r");
        command.add(Path.of(options.zapReport()).getFileName().toString());
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

    private static String zapApiTarget(SecurityScanOptions options) {
        if (!isLocalSpec(options.zapApiSpec())) {
            return options.zapApiSpec();
        }
        Path root = options.root().toAbsolutePath().normalize();
        Path spec = Path.of(options.zapApiSpec()).toAbsolutePath().normalize();
        if (!spec.startsWith(root)) {
            throw new IllegalArgumentException("Local --zap-api-spec must be under --root: " + spec);
        }
        return "/zap/src/" + root.relativize(spec).toString().replace('\\', '/');
    }

    private static boolean isLocalSpec(String value) {
        return value != null
                && !value.startsWith("http://")
                && !value.startsWith("https://")
                && !value.startsWith("/zap/");
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
