package io.github.pinpols.batch.worker.processes.cleanup;

import java.time.Instant;

/** 已确认可观测代际中过期暂存行数及该代际最早暂存时间。 */
public record ProcessStagingGenerationObservation(long overdueRows, Instant oldestRowAt) {}
