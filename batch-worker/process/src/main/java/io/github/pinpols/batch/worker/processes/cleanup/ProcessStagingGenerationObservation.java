package io.github.pinpols.batch.worker.processes.cleanup;

import java.time.Instant;

/** Tenant-safe observation of retained rows for a platform-verified stale generation. */
public record ProcessStagingGenerationObservation(long overdueRows, Instant oldestRowAt) {}
