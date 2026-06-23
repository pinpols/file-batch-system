package io.github.pinpols.batch.securityscan;

public final class SecurityScanApplication {

    private SecurityScanApplication() {
    }

    public static void main(String[] args) {
        SecurityScanOptions options = SecurityScanOptions.parse(args);
        if (options.help()) {
            printUsage();
            return;
        }

        SecurityScanOrchestrator orchestrator = new SecurityScanOrchestrator(new ProcessCommandExecutor());
        ScanReport report = orchestrator.run(options);
        report.printSummary();

        if (report.hasFailures()) {
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("""
            Thin security scan orchestrator.

            Usage:
              java -jar security-scan.jar --mode=all --root=. --target-url=http://localhost:8080

            Options:
              --mode=all|secret|deps|sast|filesystem|image|dast
              --root=.
              --report-dir=target/security-scan-report
              --target-url=http://localhost:8080
              --image-name=batch-console-api:local
              --zap-report=target/zap-report.html
              --zap-scan=baseline|api|full
              --zap-api-spec=docs/api/console-api.openapi.yaml
              --continue-on-error
              --dry-run
              --help
            """);
    }
}
