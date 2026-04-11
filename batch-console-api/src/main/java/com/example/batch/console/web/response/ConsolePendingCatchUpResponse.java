package com.example.batch.console.web.response;

import java.time.Instant;
import java.time.LocalDate;

public record ConsolePendingCatchUpResponse(
        Long id,
        String tenantId,
        String requestId,
        String jobCode,
        LocalDate bizDate,
        String requestStatus,
        String traceId,
        Instant createdAt,
        Instant updatedAt) {}
