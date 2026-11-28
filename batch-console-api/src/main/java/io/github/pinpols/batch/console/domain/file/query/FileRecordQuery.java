package io.github.pinpols.batch.console.domain.file.query;

import java.time.Instant;
import java.time.LocalDate;

import lombok.Builder;

import io.github.pinpols.batch.common.model.PageRequest;

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
