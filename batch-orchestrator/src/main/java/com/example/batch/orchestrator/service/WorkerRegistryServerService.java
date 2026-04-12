package com.example.batch.orchestrator.service;

import com.example.batch.common.dto.WorkerHeartbeatDto;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;

public interface WorkerRegistryServerService {

  WorkerRegistryRecord register(WorkerHeartbeatDto request);

  WorkerRegistryRecord heartbeat(String workerCode, WorkerHeartbeatDto request);

  void deactivate(String tenantId, String workerCode);

  WorkerRegistryRecord updateStatus(String tenantId, String workerCode, String status);
}
