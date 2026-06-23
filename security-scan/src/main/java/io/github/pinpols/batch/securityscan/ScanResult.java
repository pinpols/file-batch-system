package io.github.pinpols.batch.securityscan;

import java.time.Duration;

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
        return new ScanResult(command.step(), command.label(), String.join(" ", command.commandLine()), 0, duration, false, "ok");
    }

    public static ScanResult failed(ExternalCommand command, int exitCode, Duration duration, String message) {
        return new ScanResult(command.step(), command.label(), String.join(" ", command.commandLine()), exitCode, duration, false, message);
    }

    public static ScanResult skipped(ExternalCommand command, String message) {
        return new ScanResult(command.step(), command.label(), String.join(" ", command.commandLine()), 0, Duration.ZERO, true, message);
    }

    public boolean success() {
        return !skipped && exitCode == 0;
    }
}
