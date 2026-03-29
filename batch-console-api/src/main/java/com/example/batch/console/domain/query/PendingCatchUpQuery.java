package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record PendingCatchUpQuery(
        String tenantId,
        String jobCode,
        String requestId,
        PageRequest pageRequest
) {
}
