package io.github.pinpols.batch.securityscan;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public record ScanResult(
    ScanStep step,
    String label,
    String commandLine,
    int exitCode,
    Duration duration,
    boolean skipped,
    String message) {

  public static ScanResult success(ExternalCommand command, Duration duration) {
    ScanStep step = command.step();
    String label = command.label();
    List<String> commandTokens = command.commandLine();
    String renderedCommandLine = renderCommand(command, commandTokens);
    return new ScanResult(step, label, renderedCommandLine, 0, duration, false, "ok");
  }

  public static ScanResult failed(
      ExternalCommand command, int exitCode, Duration duration, String message) {
    ScanStep step = command.step();
    String label = command.label();
    List<String> commandTokens = command.commandLine();
    String renderedCommandLine = renderCommand(command, commandTokens);
    return new ScanResult(step, label, renderedCommandLine, exitCode, duration, false, message);
  }

  public static ScanResult skipped(ExternalCommand command, String message) {
    ScanStep step = command.step();
    String label = command.label();
    List<String> commandTokens = command.commandLine();
    String renderedCommandLine = renderCommand(command, commandTokens);
    return new ScanResult(step, label, renderedCommandLine, 0, Duration.ZERO, true, message);
  }

  public boolean success() {
    return !skipped && exitCode == 0;
  }

  private static String renderCommand(ExternalCommand command, List<String> tokens) {
    if ("semgrep-report".equals(command.label())) {
      return "python3 <bundled semgrep_report.py> " + tokens.getLast();
    }
    if (command.step() == ScanStep.DAST) {
      int authOption = tokens.lastIndexOf("-z");
      if (authOption >= 0 && authOption + 1 < tokens.size()) {
        List<String> safeTokens = new ArrayList<>(tokens);
        safeTokens.set(authOption + 1, "<redacted-zap-config>");
        return String.join(" ", safeTokens);
      }
    }
    return String.join(" ", tokens);
  }
}
