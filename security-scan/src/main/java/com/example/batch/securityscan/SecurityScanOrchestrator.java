package com.example.batch.securityscan;

import java.util.List;

public final class SecurityScanOrchestrator {

    private final ProcessCommandExecutor executor;

    public SecurityScanOrchestrator(ProcessCommandExecutor executor) {
        this.executor = executor;
    }

    public ScanReport run(SecurityScanOptions options) {
        ScanReport report = new ScanReport();
        List<ScanStep> steps = options.mode() == ScanMode.ALL
                ? List.of(ScanStep.SECRET, ScanStep.DEPS, ScanStep.SAST, ScanStep.FILESYSTEM, ScanStep.IMAGE, ScanStep.DAST)
                : List.of(stepOf(options.mode()));

        for (ScanStep step : steps) {
            for (ExternalCommand command : step.buildCommands(options)) {
                System.out.println("==> " + step.displayName() + " / " + command.label());
                ScanResult result = executor.execute(command, options.dryRun());
                report.add(result);
                if (!options.continueOnError() && result.exitCode() != 0 && !result.skipped()) {
                    return report;
                }
            }
        }

        return report;
    }

    private static ScanStep stepOf(ScanMode mode) {
        return switch (mode) {
            case SECRET -> ScanStep.SECRET;
            case DEPS -> ScanStep.DEPS;
            case SAST -> ScanStep.SAST;
            case FILESYSTEM -> ScanStep.FILESYSTEM;
            case IMAGE -> ScanStep.IMAGE;
            case DAST -> ScanStep.DAST;
            case ALL -> throw new IllegalArgumentException("ALL is not a single step");
        };
    }
}
