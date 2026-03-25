package com.example.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP client settings for worker → orchestrator task execution ({@code claim} / {@code report} / {@code renew}).
 *
 * <p>Defaults favour production-safe timeouts; override via {@code batch.worker.task-client.*} (YAML or env).
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.task-client")
public class OrchestratorTaskClientProperties {

    private String baseUrl;
    private int batchSize = 10;

    /** Connect timeout when opening the TCP connection to the orchestrator. */
    private int connectTimeoutMillis = 5_000;

    /** Read timeout waiting for an HTTP response body from the orchestrator. */
    private int readTimeoutMillis = 30_000;

    /** Maximum attempts for {@code POST /report} including the first call; 5xx and I/O errors are retried with backoff. */
    private int reportMaxAttempts = 4;

    private int reportInitialBackoffMillis = 200;
    private int reportMaxBackoffMillis = 5_000;

    /** Maximum attempts for {@code claim} / {@code renew} on transient upstream errors (5xx, timeouts). */
    private int claimMaxAttempts = 4;

    private int claimInitialBackoffMillis = 200;
    private int claimMaxBackoffMillis = 5_000;
}
