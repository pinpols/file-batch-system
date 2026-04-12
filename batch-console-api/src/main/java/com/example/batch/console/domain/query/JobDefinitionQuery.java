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
        PageRequest pageRequest) {

    /** 按租户全量查询，不带过滤条件。 */
    public static JobDefinitionQuery ofTenant(String tenantId, PageRequest pageRequest) {
        return new JobDefinitionQuery(tenantId, null, null, null, null, null, null, null, pageRequest);
    }
}
