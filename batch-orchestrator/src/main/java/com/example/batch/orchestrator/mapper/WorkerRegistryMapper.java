package com.example.batch.orchestrator.mapper;

import org.apache.ibatis.annotations.Param;

import java.time.Instant;

public interface WorkerRegistryMapper {

    int touchHeartbeat(TouchHeartbeatParam param);

    int markDecommissioned(
            @Param("tenantId") String tenantId,
            @Param("workerCode") String workerCode,
            @Param("heartbeatAt") Instant heartbeatAt);
}
