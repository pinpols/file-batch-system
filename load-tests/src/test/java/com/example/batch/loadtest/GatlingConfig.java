package com.example.batch.loadtest;

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
}
