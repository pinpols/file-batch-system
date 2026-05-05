package com.example.batch.securityscan;
import com.example.batch.common.time.BatchDateTimeSupport;
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

        Instant start = BatchDateTimeSupport.utcNow();
        try {
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
            Duration duration = Duration.between(start, BatchDateTimeSupport.utcNow());
            return exitCode == 0
                    ? ScanResult.success(command, duration)
                    : ScanResult.failed(command, exitCode, duration, "exit code: " + exitCode);
        } catch (IOException e) {
            Duration duration = Duration.between(start, BatchDateTimeSupport.utcNow());
            return ScanResult.failed(command, 127, duration, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Duration duration = Duration.between(start, BatchDateTimeSupport.utcNow());
            return ScanResult.failed(command, 130, duration, "interrupted");
        }
    }
}
