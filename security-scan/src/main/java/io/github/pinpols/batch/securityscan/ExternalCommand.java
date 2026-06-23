package io.github.pinpols.batch.securityscan;

import java.nio.file.Path;
import java.util.List;

public record ExternalCommand(
        ScanStep step,
        String label,
        List<String> commandLine,
        Path workingDirectory
) {
}
