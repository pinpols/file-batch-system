package com.example.batch.securityscan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ScanReport {

    private final List<ScanResult> results = new ArrayList<>();

    public void add(ScanResult result) {
        results.add(result);
    }

    public List<ScanResult> results() {
        return Collections.unmodifiableList(results);
    }

    public boolean hasFailures() {
        return results.stream().anyMatch(result -> !result.skipped() && result.exitCode() != 0);
    }

    public void printSummary() {
        System.out.println();
        System.out.println("=== Security Scan Summary ===");
        for (ScanResult result : results) {
            String status = result.skipped() ? "SKIPPED" : (result.exitCode() == 0 ? "OK" : "FAILED");
            System.out.println(status + " | " + result.step().displayName() + " | " + result.label() + " | " + result.duration().toSeconds() + "s");
            if (!result.skipped() && result.exitCode() != 0) {
                System.out.println("  command: " + result.commandLine());
                System.out.println("  message: " + result.message());
            }
        }
        System.out.println("============================");
    }
}
