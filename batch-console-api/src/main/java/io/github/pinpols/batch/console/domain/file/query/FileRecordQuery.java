package io.github.pinpols.batch.console.domain.file.query;

import io.github.pinpols.batch.common.model.PageRequest;
import java.time.Instant;

public record FileRecordQuery(
    String tenantId,
    String bizType,
    String fileStatus,
    Long fileId,
    String fileName,
    String traceId,
    Instant fromTime,
    Instant toTime,
    PageRequest pageRequest) {}
