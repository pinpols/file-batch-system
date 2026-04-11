package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record JobDefinitionQuery(
        String tenantId,
        String jobCode,
        String jobName,
        String jobType,
        String workerGroup,
        String queueCode,
        String scheduleType,
        Boolean enabled,
        PageRequest pageRequest) {}
