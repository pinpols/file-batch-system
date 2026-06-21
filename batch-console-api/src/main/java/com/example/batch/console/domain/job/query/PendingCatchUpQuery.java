package com.example.batch.console.domain.job.query;

import com.example.batch.common.model.PageRequest;

public record PendingCatchUpQuery(
    String tenantId,
    String jobCode,
    String requestId,
    String bizDate,
    String keyword,
    PageRequest pageRequest,
    Long cursorId) {}
