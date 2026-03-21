package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record JobDefinitionQuery(
        String tenantId,
        String jobCode,
        String jobType,
        Boolean enabled,
        PageRequest pageRequest
) {
}
