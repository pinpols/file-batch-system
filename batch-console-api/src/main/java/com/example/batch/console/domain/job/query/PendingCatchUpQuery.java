package com.example.batch.console.domain.job.query;

import com.example.batch.common.model.PageRequest;

public record PendingCatchUpQuery(
    String tenantId, String jobCode, String requestId, PageRequest pageRequest, Long cursorId) {}
