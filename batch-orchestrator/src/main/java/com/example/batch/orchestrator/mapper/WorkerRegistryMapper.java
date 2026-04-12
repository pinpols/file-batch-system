package com.example.batch.orchestrator.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Param;

public interface WorkerRegistryMapper {

  int touchHeartbeat(TouchHeartbeatParam param);

  int markDecommissioned(
      @Param("tenantId") String tenantId,
      @Param("workerCode") String workerCode,
      @Param("heartbeatAt") Instant heartbeatAt);
}
