package com.example.batch.worker.core.config;

/**
 * Common configuration contract for all worker types (import / export / dispatch).
 * Each worker module's {@code @ConfigurationProperties} record implements this interface
 * so the shared lifecycle and consumer templates in {@code batch-worker-core} can operate
 * without depending on module-specific configuration classes.
 */
public interface WorkerConfiguration {

    /** Stable worker identifier; when set, used directly as workerId. */
    String workerCode();

    /** Worker type discriminator (e.g. "IMPORT", "EXPORT", "DISPATCH"). */
    String workerType();

    /** Tenant this worker instance belongs to. */
    String tenantId();

    /** Heartbeat interval in milliseconds (nullable – callers must apply a default). */
    Long heartbeatIntervalMillis();

    /** Kafka topic this worker consumes from. */
    String topic();

    /** Kafka consumer group id. */
    String consumerGroupId();
}
