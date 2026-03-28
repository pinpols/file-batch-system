package com.example.batch.securityscan;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

public record SecurityScanOptions(
        boolean help,
        ScanMode mode,
        Path root,
        String targetUrl,
        String imageName,
        String zapImage,
        String zapReport,
        Path reportDir,
        String mvnCommand,
        String gitleaksCommand,
        String semgrepCommand,
        String trivyCommand,
        String dockerCommand,
        boolean continueOnError,
        boolean dryRun
) {

    public static SecurityScanOptions parse(String[] args) {
        boolean help = false;
        ScanMode mode = ScanMode.ALL;
        Path root = defaultRoot();
        String targetUrl = "http://localhost:8080";
        String imageName = "batch-console-api:local";
        String zapImage = "ghcr.io/zaproxy/zaproxy:stable";
        Path reportDir = defaultReportDir(root);
        String zapReport = reportDir.resolve("zap-report.html").toString();
        String mvnCommand = "mvn";
        String gitleaksCommand = "gitleaks";
        String semgrepCommand = "semgrep";
        String trivyCommand = "trivy";
        String dockerCommand = "docker";
        boolean continueOnError = false;
        boolean dryRun = false;

        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                help = true;
                continue;
            }
            if ("--continue-on-error".equals(arg)) {
                continueOnError = true;
                continue;
            }
            if ("--dry-run".equals(arg)) {
                dryRun = true;
                continue;
            }

            int separator = arg.indexOf('=');
            if (!arg.startsWith("--") || separator < 0) {
                throw new IllegalArgumentException("Unsupported argument: " + arg);
            }

            String key = arg.substring(2, separator).toLowerCase(Locale.ROOT);
            String value = arg.substring(separator + 1);
            switch (key) {
                case "mode" -> mode = ScanMode.fromCli(value);
                case "root" -> root = Paths.get(value).toAbsolutePath().normalize();
                case "target-url" -> targetUrl = value;
                case "image-name" -> imageName = value;
                case "zap-image" -> zapImage = value;
                case "report-dir" -> {
                    reportDir = Paths.get(value).toAbsolutePath().normalize();
                    zapReport = reportDir.resolve("zap-report.html").toString();
                }
                case "zap-report" -> zapReport = value;
                case "mvn" -> mvnCommand = value;
                case "gitleaks" -> gitleaksCommand = value;
                case "semgrep" -> semgrepCommand = value;
                case "trivy" -> trivyCommand = value;
                case "docker" -> dockerCommand = value;
                default -> throw new IllegalArgumentException("Unsupported argument: " + arg);
            }
        }

        return new SecurityScanOptions(
                help,
                mode,
                root,
                targetUrl,
                imageName,
                zapImage,
                zapReport,
                reportDir,
                mvnCommand,
                gitleaksCommand,
                semgrepCommand,
                trivyCommand,
                dockerCommand,
                continueOnError,
                dryRun
        );
    }

    private static Path defaultRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        Path fileName = current.getFileName();
        if (fileName != null && "security-scan".equals(fileName.toString()) && current.getParent() != null) {
            return current.getParent();
        }
        return current;
    }

    private static Path defaultReportDir(Path root) {
        Path reportDir = root.resolve("target").resolve("security-scan-report");
        try {
            Files.createDirectories(reportDir);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create report directory: " + reportDir, e);
        }
        return reportDir;
    }
}
