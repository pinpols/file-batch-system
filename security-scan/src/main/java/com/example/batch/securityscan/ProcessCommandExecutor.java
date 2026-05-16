package com.example.batch.securityscan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

public final class ProcessCommandExecutor {

    public ScanResult execute(ExternalCommand command, boolean dryRun) {
        if (dryRun) {
            return ScanResult.skipped(command, "dry-run");
        }

        Instant start = Instant.now();
        try {
            // R7 安全扫描 2026-05-16: semgrep command-injection-process-builder 标红。
            // 实际 ProcessBuilder 接受的 commandLine() 来自 ScanStep.buildCommands()，
            // 字符串元素是**编译期常量 + Options 字段**（mvn / gitleaks / trivy / docker 命令名 +
            // hardcoded 参数）。没有用户输入路径进入 commandLine。本类**仅**由 SecurityScanOrchestrator
            // 在 main() 启动时调用，不接 HTTP / RPC / 任何外部输入。
            // ProcessBuilder 用 List<String> 而非 shell 字符串，参数已经按 token 分隔，shell
            // metacharacter 不会被解析。结论：无注入风险，保留当前实现。
            Process process = new ProcessBuilder(command.commandLine())
                    .directory(command.workingDirectory().toFile())
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[" + command.step().displayName() + "] " + line);
                }
            }

            int exitCode = process.waitFor();
            Duration duration = Duration.between(start, Instant.now());
            return exitCode == 0
                    ? ScanResult.success(command, duration)
                    : ScanResult.failed(command, exitCode, duration, "exit code: " + exitCode);
        } catch (IOException e) {
            Duration duration = Duration.between(start, Instant.now());
            return ScanResult.failed(command, 127, duration, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Duration duration = Duration.between(start, Instant.now());
            return ScanResult.failed(command, 130, duration, "interrupted");
        }
    }
}
