package io.github.pinpols.batch.securityscan;

import java.time.Duration;
import java.util.List;

public record ScanResult(
        ScanStep step,
        String label,
        String commandLine,
        int exitCode,
        Duration duration,
        boolean skipped,
        String message
) {

    public static ScanResult success(ExternalCommand command, Duration duration) {
        ScanStep step = command.step();
        String label = command.label();
        List<String> commandTokens = command.commandLine();
        String renderedCommandLine = String.join(" ", commandTokens);
        return new ScanResult(step, label, renderedCommandLine, 0, duration, false, "ok");
    }

    public static ScanResult failed(ExternalCommand command, int exitCode, Duration duration, String message) {
        ScanStep step = command.step();
        String label = command.label();
        List<String> commandTokens = command.commandLine();
        String renderedCommandLine = String.join(" ", commandTokens);
        return new ScanResult(step, label, renderedCommandLine, exitCode, duration, false, message);
    }

    public static ScanResult skipped(ExternalCommand command, String message) {
        ScanStep step = command.step();
        String label = command.label();
        List<String> commandTokens = command.commandLine();
        String renderedCommandLine = String.join(" ", commandTokens);
        return new ScanResult(step, label, renderedCommandLine, 0, Duration.ZERO, true, message);
    }

    public boolean success() {
        return !skipped && exitCode == 0;
    }
}
