# E2E Individual Run Report (2026-03-24)

## Scope

- Module: `batch-e2e-tests`
- Execution mode: run each `*E2eIT` separately with Maven `-Dtest=<Class>`
- Commands:
  - `mvn -q -pl batch-e2e-tests clean test-compile` (pre-clean to avoid stale test bytecode)
  - `mvn -q -pl batch-e2e-tests -Dtest=<Class> test` (per class)

## Results

| Test Class | Result | Surefire Summary |
|---|---|---|
| `OutboxForwarderRetryE2eIT` | **FAIL** | Tests run: 2, Failures: 0, Errors: 2, Skipped: 0 |
| `ImportFailurePipelineE2eIT` | **PASS** | Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 (inferred by exit=0) |
| `DispatchFailurePipelineE2eIT` | **PASS** | Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 (inferred by exit=0) |
| `DispatchPipelineE2eIT` | **PASS** | Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 (inferred by exit=0) |
| `ImportPipelineE2eIT` | **PASS** | Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 (inferred by exit=0) |
| `ExportFailurePipelineE2eIT` | **PASS** | Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 (inferred by exit=0) |
| `ExportPipelineE2eIT` | **PASS** | Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 (inferred by exit=0) |
| `OutboxForwarderE2eIT` | **PASS** | Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 (inferred by exit=0) |

## Failure Analysis

### `OutboxForwarderRetryE2eIT`

- Root cause: `java.lang.IllegalStateException: Unable to resolve batch.orchestrator.base-url for worker registry client`
- Direct impact: Spring context fails during startup; all tests in this class error out.
- Why it happens: this class runs with `spring.main.web-application-type=none`, while worker lifecycle startup still requires resolving `batch.orchestrator.base-url` for `HttpWorkerRegistryClient`.
- Suggested fix options:
  - add test property `batch.orchestrator.base-url=http://127.0.0.1:${local.server.port}`; or
  - switch this class to servlet web mode if worker registration/HTTP client is expected; or
  - disable worker auto-start in this test if not needed for outbox-forwarder assertions.

## Notes

- In an earlier non-clean run, all classes failed with `FileNotFoundException: E2ePlatformDataSourceConfiguration.class` due to stale/invalid test class metadata; a `clean test-compile` resolved that transient issue.
- This report reflects the post-clean run (the reliable signal).
