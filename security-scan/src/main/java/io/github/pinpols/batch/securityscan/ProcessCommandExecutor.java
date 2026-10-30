package io.github.pinpols.batch.securityscan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
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
      // 扫描命令由 ScanStep 构造；工具路径及部分参数来自操作人员的命令行选项。
      // 参数按 token 直接传递给进程，不经 shell 解析；选定工具仍会解释自己的参数。
      Process process = new ProcessBuilder(command.commandLine())
          .directory(command.workingDirectory().toFile())
          .redirectErrorStream(true)
          .start();

      InputStream processOutput = process.getInputStream();
      InputStreamReader utf8Reader = new InputStreamReader(processOutput, StandardCharsets.UTF_8);
      try (BufferedReader reader = new BufferedReader(utf8Reader)) {
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
