package com.example.batch.console.web.response;

import java.time.Instant;

public record ConsoleSchedulerSnapshotHistoryResponse(
        Long id,
        String tenantId,
        Instant snapshotAt,
        String fairShareGroup,
        String policyCode,
        Integer activeJobs,
        Integer activePartitions,
        Integer maxJobsBase,
        Integer burstLimit,
        Integer effectiveJobCap,
        Integer groupActiveJobs,
        Integer groupMaxJobs,
        String quotaResetPolicy,
        Integer onlineWorkers,
        Object detailJson
) {
}
