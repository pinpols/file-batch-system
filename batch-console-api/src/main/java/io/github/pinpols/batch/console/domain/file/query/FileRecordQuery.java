package io.github.pinpols.batch.console.domain.file.query;

import io.github.pinpols.batch.common.model.PageRequest;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

@Builder
public record FileRecordQuery(
    String tenantId,
    String bizType,
    String fileStatus,
    Long fileId,
    String fileName,
    String jobCode,
    String traceId,
    LocalDate startDate,
    LocalDate endDate,
    Instant fromTime,
    Instant toTime,
    PageRequest pageRequest,
    Long cursorId) {}
