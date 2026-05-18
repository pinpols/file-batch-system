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
        String zapAuthHeaderName,
        String zapAuthHeaderValue,
        boolean requireZapAuth,
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
        String zapAuthHeaderName = envOrDefault("BATCH_DAST_AUTH_HEADER_NAME", "Authorization");
        String zapAuthHeaderValue = firstNonBlank(
                System.getenv("BATCH_DAST_AUTH_HEADER_VALUE"),
                System.getenv("ZAP_AUTH_HEADER_VALUE"));
        boolean requireZapAuth = false;
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
            if ("--require-zap-auth".equals(arg)) {
                requireZapAuth = true;
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
                case "zap-auth-header-name" -> zapAuthHeaderName = value;
                case "zap-auth-header-value" -> zapAuthHeaderValue = value;
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

        if (requireZapAuth && isBlank(zapAuthHeaderValue)) {
            throw new IllegalArgumentException(
                    "--require-zap-auth set but no DAST auth header value was provided. "
                            + "Set BATCH_DAST_AUTH_HEADER_VALUE or pass --zap-auth-header-value=...");
        }

        return new SecurityScanOptions(
                help,
                mode,
                root,
                targetUrl,
                imageName,
                zapImage,
                zapReport,
                zapAuthHeaderName,
                zapAuthHeaderValue,
                requireZapAuth,
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

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return isBlank(value) ? fallback : value;
    }

    private static String firstNonBlank(String first, String second) {
        if (!isBlank(first)) {
            return first;
        }
        return isBlank(second) ? null : second;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
