package com.example.batch.common.dto;

import java.time.Instant;
import java.util.List;

public record WorkerHeartbeatDto(
        String tenantId,
        String workerCode,
        String workerGroup,
        String status,
        String hostName,
        String hostIp,
        String processId,
        Instant heartbeatAt,
        List<String> capabilityTags,
        Integer currentLoad
) {
    public WorkerHeartbeatDto {
        currentLoad = (currentLoad != null && currentLoad < 0) ? 0 : currentLoad;
    }
}
