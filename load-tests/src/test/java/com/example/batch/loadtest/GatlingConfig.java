package com.example.batch.loadtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Centralized load-test configuration read from system properties (set via -D flags or
 * Maven profiles).  All simulations pull their parameters from here.
 */
public final class GatlingConfig {

    // ── Endpoints ──────────────────────────────────────────────────────────────

    /** batch-trigger base URL, e.g. http://localhost:8081 */
    public static final String TRIGGER_BASE_URL =
            System.getProperty("trigger.baseUrl", "http://localhost:8081");

    /** batch-console-api base URL, e.g. http://localhost:8080 */
    public static final String CONSOLE_BASE_URL =
            System.getProperty("console.baseUrl", "http://localhost:8080");

    /**
     * batch-orchestrator base URL for {@code /internal/**} probes (default aligns with local
     * {@code BATCH_ORCHESTRATOR_PORT=18082}).
     */
    public static final String ORCHESTRATOR_BASE_URL =
            System.getProperty("orchestrator.baseUrl", "http://localhost:18082");

    /** Shared secret required by local trigger endpoints. */
    public static final String INTERNAL_SECRET =
            System.getProperty("internal.secret", "internal-secret");

    // ── Test data ──────────────────────────────────────────────────────────────

    /** Tenant ID used in load test requests. */
    public static final String TENANT_ID =
            System.getProperty("tenantId", "t1");

    /**
     * Pre-existing job code (must be seeded in the target DB before running).
     * Align with {@code docs/sql/load-test/load-test-seed.sql} or
     * {@code batch-e2e-tests/src/test/resources/db/testdata/import-template-config-seed.sql} job codes.
     */
    public static final String JOB_CODE =
            System.getProperty("jobCode", "E2E_IMPORT_LOAD");

    /** Business date for job launch requests (ISO-8601). */
    public static final String BIZ_DATE =
            System.getProperty("bizDate", "2026-01-15");

    /**
     * JSON object inserted as the launch {@code params}. Prefer {@code launch.paramsJsonFile} for
     * large import payloads to avoid command-line length limits.
     */
    public static final String LAUNCH_PARAMS_JSON = resolveLaunchParamsJson();

    // ── Load profile ───────────────────────────────────────────────────────────

    /** Peak concurrent virtual users. */
    public static final int USERS_PEAK =
            Integer.parseInt(System.getProperty("users.peak", "20"));

    /** Steady-state duration in seconds after ramp-up completes. */
    public static final int DURATION_SECONDS =
            Integer.parseInt(System.getProperty("duration.seconds", "120"));

    /** Ramp-up duration in seconds. */
    public static final int RAMP_SECONDS =
            Integer.parseInt(System.getProperty("ramp.seconds", "30"));

    /**
     * Max polls after launch in {@code LaunchPipelineCompletionSimulation} (each poll separated by
     * {@link #PIPELINE_POLL_INTERVAL_SEC}).
     */
    public static final int PIPELINE_MAX_POLLS =
            Integer.parseInt(System.getProperty("pipeline.maxPolls", "90"));

    /** Seconds to wait between instance status polls. */
    public static final int PIPELINE_POLL_INTERVAL_SEC =
            Integer.parseInt(System.getProperty("pipeline.pollIntervalSec", "2"));

    /**
     * Concurrent virtual users for {@code LaunchPipelineCompletionSimulation} (each holds long poll
     * loops; keep lower than generic {@link #USERS_PEAK}).
     */
    public static final int PIPELINE_COMPLETION_USERS =
            Integer.parseInt(System.getProperty("pipeline.completion.users", "10"));

    /** Fixed write rate used by scheduling/backlog pressure scenarios. */
    public static final double SCHEDULING_LAUNCH_RPS =
            Double.parseDouble(System.getProperty("scheduling.launch.rps", "5.0"));

    /** Fixed scheduler/backlog read rate used by scheduling pressure scenarios. */
    public static final double SCHEDULING_READ_RPS =
            Double.parseDouble(System.getProperty("scheduling.read.rps", "5.0"));

    /** CSV file for synthetic worker lifecycle pressure: taskId,tenantId,workerId. */
    public static final String TASK_LIFECYCLE_CSV =
            System.getProperty("task.lifecycle.csv", "target/task-lifecycle-tasks.csv");

    /** Optional pause between successful claim and report in synthetic worker lifecycle pressure. */
    public static final int TASK_LIFECYCLE_EXECUTE_PAUSE_MS =
            Integer.parseInt(System.getProperty("task.lifecycle.executePauseMs", "0"));

    // ── SLO thresholds ────────────────────────────────────────────────────────

    /** Maximum acceptable p95 response time for write operations (ms). */
    public static final int WRITE_P95_MS =
            Integer.parseInt(System.getProperty("slo.write.p95ms", "500"));

    /** Maximum acceptable p99 response time for read operations (ms). */
    public static final int READ_P99_MS =
            Integer.parseInt(System.getProperty("slo.read.p99ms", "300"));

    /** Maximum acceptable error rate percentage (0–100). */
    public static final double MAX_ERROR_RATE_PCT =
            Double.parseDouble(System.getProperty("slo.maxErrorPct", "1.0"));

    private GatlingConfig() {
    }

    private static String resolveLaunchParamsJson() {
        String file = System.getProperty("launch.paramsJsonFile");
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read launch.paramsJsonFile=" + file, e);
            }
        }
        return System.getProperty("launch.paramsJson", "{}");
    }
}
